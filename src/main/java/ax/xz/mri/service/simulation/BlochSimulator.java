package ax.xz.mri.service.simulation;

import ax.xz.mri.model.field.FieldInterpolator;
import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.field.FieldPoint;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.simulation.MagnetisationState;
import ax.xz.mri.model.simulation.Trajectory;
import ax.xz.mri.service.circuit.CircuitStepEvaluator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Core Bloch equation simulator.
 *
 * <p>Integrates in the rotating frame at {@code ω_s = γ · b0Ref}. Each
 * pulse step has its per-coil (I, Q) drives precomputed once when the pulse
 * is compiled; {@link #applyStep} then just sums
 * {@code Σ_c (I_c·E_c − jQ_c·E_c) + staticBz·ẑ} at the current grid point and
 * applies Rodrigues' rotation plus T1/T2 decay.
 */
public final class BlochSimulator {
    private static final int COMPILED_CACHE_SIZE = 24;
    private static final int POINT_CONTEXT_CACHE_SIZE = 4096;
    private static final int TRAJECTORY_CACHE_SIZE = 32;
    private static final int TRAJECTORY_CACHE_STEP_LIMIT = 200_000;
    private static final int CURSOR_CACHE_SIZE = 4096;
    private static final int CURSOR_CHECKPOINT_INTERVAL = 64;

    private static final BlochSimulator DEFAULT = new BlochSimulator(true);

    private final Map<PulseKey, CompiledPulse> compiledCache;
    private final Map<SimulationKey, PointContext> pointContextCache;
    private final Map<SimulationKey, Trajectory> trajectoryCache;
    private final Map<SimulationKey, CursorStateCache> cursorCache;

    public BlochSimulator() { this(false); }

    private BlochSimulator(boolean ignored) {
        compiledCache = synchronizedLruCache(COMPILED_CACHE_SIZE);
        pointContextCache = synchronizedLruCache(POINT_CONTEXT_CACHE_SIZE);
        trajectoryCache = synchronizedLruCache(TRAJECTORY_CACHE_SIZE);
        cursorCache = synchronizedLruCache(CURSOR_CACHE_SIZE);
    }

    public static Trajectory simulate(BlochData data, double rMm, double zMm, List<PulseSegment> pulse) {
        return DEFAULT.simulateCached(data, rMm, zMm, pulse);
    }

    public static MagnetisationState simulateTo(BlochData data, double rMm, double zMm, List<PulseSegment> pulse, double tcMicros) {
        return DEFAULT.simulateToCached(data, rMm, zMm, pulse, tcMicros);
    }

    public static void clearCachesForTests() {
        DEFAULT.compiledCache.clear();
        DEFAULT.pointContextCache.clear();
        DEFAULT.trajectoryCache.clear();
        DEFAULT.cursorCache.clear();
    }

    private Trajectory simulateCached(BlochData data, double rMm, double zMm, List<PulseSegment> pulse) {
        if (data == null || data.field() == null || pulse == null) return null;
        var key = simulationKey(data.field(), pulse, rMm, zMm);
        var cached = cacheGet(trajectoryCache, key);
        if (cached != null) return cached;

        var context = pointContextFor(data.field(), pulse, rMm, zMm, key);
        var computed = simulateFull(context);
        if (computed != null && computed.pointCount() <= TRAJECTORY_CACHE_STEP_LIMIT) {
            cachePut(trajectoryCache, key, computed);
        }
        return computed;
    }

    private MagnetisationState simulateToCached(BlochData data, double rMm, double zMm, List<PulseSegment> pulse, double tcMicros) {
        if (data == null || data.field() == null || pulse == null) {
            return MagnetisationState.THERMAL_EQUILIBRIUM;
        }
        var key = simulationKey(data.field(), pulse, rMm, zMm);
        var trajectory = cacheGet(trajectoryCache, key);
        if (trajectory != null) {
            return trajectory.stepStateAt(tcMicros);
        }

        var context = pointContextFor(data.field(), pulse, rMm, zMm, key);
        var stateCache = cacheGetOrCreate(cursorCache, key, () -> new CursorStateCache(context.fieldPoint()));
        return stateCache.stateAt(context.compiledPulse(), context.fieldPoint(), tcMicros);
    }

    private PointContext pointContextFor(FieldMap field, List<PulseSegment> pulse, double rMm, double zMm, SimulationKey key) {
        return cacheGetOrCreate(pointContextCache, key, () -> new PointContext(
            compiledPulseFor(field, pulse),
            FieldInterpolator.interpolate(field, rMm, zMm)
        ));
    }

    private CompiledPulse compiledPulseFor(FieldMap field, List<PulseSegment> pulse) {
        var key = new PulseKey(field, pulse);
        return cacheGetOrCreate(compiledCache, key, () -> compilePulse(field, pulse));
    }

    private static CompiledPulse compilePulse(FieldMap field, List<PulseSegment> pulse) {
        int stepCount = 0;
        for (int segmentIndex = 0; segmentIndex < field.segments.size() && segmentIndex < pulse.size(); segmentIndex++) {
            stepCount += pulse.get(segmentIndex).steps().size();
        }
        var kernels = new StepKernel[stepCount];
        var stateTimesMicros = new double[stepCount + 1];
        double timeMicros = 0;
        double timeSeconds = 0;
        int kernelIndex = 0;
        stateTimesMicros[0] = 0;

        var compiled = field.circuit;
        int nCoils = compiled == null ? 0 : compiled.coils().size();
        double[][] coilI = new double[stepCount][nCoils];
        double[][] coilQ = new double[stepCount][nCoils];
        double omegaSim = field.gamma * field.b0Ref;

        // Transmit-only pre-pass: solve the MNA once per step with no
        // reciprocity EMF feedback, and cache the resulting coil currents.
        // Receive (probe voltages + EMF coupling back into the solve) happens
        // later in SignalTraceComputer where magnetisation state is known.
        var evaluator = compiled == null ? null : new CircuitStepEvaluator(compiled);

        for (int segmentIndex = 0; segmentIndex < field.segments.size() && segmentIndex < pulse.size(); segmentIndex++) {
            var segment = field.segments.get(segmentIndex);
            double dt = segment.dt();
            double e2 = Math.exp(-dt / field.t2);
            double e1 = Math.exp(-dt / field.t1);
            for (var step : pulse.get(segmentIndex).steps()) {
                if (evaluator != null) {
                    evaluator.evaluate(step.controls(), dt, null, null, timeSeconds, omegaSim);
                    for (int c = 0; c < nCoils; c++) {
                        coilI[kernelIndex][c] = evaluator.coilDriveI(c);
                        coilQ[kernelIndex][c] = evaluator.coilDriveQ(c);
                    }
                }
                kernels[kernelIndex] = new StepKernel(step, dt, e1, e2, timeSeconds);
                timeMicros += dt * 1e6;
                timeSeconds += dt;
                stateTimesMicros[++kernelIndex] = timeMicros;
            }
        }
        return new CompiledPulse(field, kernels, stateTimesMicros, coilI, coilQ);
    }

    private static Trajectory simulateFull(PointContext context) {
        var compiled = context.compiledPulse();
        var field = compiled.field();
        var point = context.fieldPoint();

        double mx = point.mx0();
        double my = point.my0();
        double mz = point.mz0();
        int stepCount = compiled.kernels().length;
        var out = new double[(stepCount + 1) * 5];
        int oi = 0;

        for (int stepIndex = 0; stepIndex < stepCount; stepIndex++) {
            out[oi++] = round1(compiled.stateTimesMicros()[stepIndex]);
            out[oi++] = round5(mx);
            out[oi++] = round5(my);
            out[oi++] = round5(mz);
            out[oi++] = compiled.kernels()[stepIndex].rfOn() ? 1 : 0;

            var next = applyStep(compiled, stepIndex, point, field, mx, my, mz);
            mx = next.mx();
            my = next.my();
            mz = next.mz();
        }

        out[oi++] = round1(compiled.stateTimesMicros()[stepCount]);
        out[oi++] = round5(mx);
        out[oi++] = round5(my);
        out[oi++] = round5(mz);
        out[oi] = 2;
        return new Trajectory(out);
    }

    private static MagnetisationState applyStep(CompiledPulse compiled, int stepIndex, FieldPoint point, FieldMap field,
                                                double mx, double my, double mz) {
        var kernel = compiled.kernels()[stepIndex];
        double bx = 0, by = 0, bz = point.staticBz();
        int nCoils = compiled.coilDriveI()[0].length;
        double[] iRow = compiled.coilDriveI()[stepIndex];
        double[] qRow = compiled.coilDriveQ()[stepIndex];
        for (int c = 0; c < nCoils; c++) {
            double I = iRow[c], Q = qRow[c];
            double ex = point.coilEx()[c], ey = point.coilEy()[c], ez = point.coilEz()[c];
            bx += I * ex - Q * ey;
            by += I * ey + Q * ex;
            bz += I * ez;
        }
        double bPerp2 = bx * bx + by * by;
        if (!kernel.rfOn() && bPerp2 < 1e-30) {
            double om = field.gamma * bz;
            double th = om * kernel.dt();
            double c = Math.cos(th);
            double s = Math.sin(th);
            double nmx = (mx * c - my * s) * kernel.e2();
            double nmy = (mx * s + my * c) * kernel.e2();
            return new MagnetisationState(nmx, nmy, 1 + (mz - 1) * kernel.e1());
        }
        return rodrigues(bx, by, bz, field.gamma, kernel.dt(), mx, my, mz, kernel.e2(), kernel.e1());
    }

    private static MagnetisationState rodrigues(double bx, double by, double bz,
                                                double gamma, double dt,
                                                double mx, double my, double mz,
                                                double e2, double e1) {
        double bm = Math.sqrt(bx * bx + by * by + bz * bz + 1e-60);
        double th = gamma * bm * dt;
        double nx = bx / bm;
        double ny = by / bm;
        double nz = bz / bm;
        double c = Math.cos(th);
        double s = Math.sin(th);
        double oc = 1 - c;
        double nd = nx * mx + ny * my + nz * mz;
        double cx = ny * mz - nz * my;
        double cy = nz * mx - nx * mz;
        double cz = nx * my - ny * mx;
        return new MagnetisationState(
            (mx * c + cx * s + nx * nd * oc) * e2,
            (my * c + cy * s + ny * nd * oc) * e2,
            1 + (mz * c + cz * s + nz * nd * oc - 1) * e1
        );
    }

    private static SimulationKey simulationKey(FieldMap field, List<PulseSegment> pulse, double rMm, double zMm) {
        return new SimulationKey(new PulseKey(field, pulse), Double.doubleToLongBits(rMm), Double.doubleToLongBits(zMm));
    }

    private static <K, V> Map<K, V> synchronizedLruCache(int maxEntries) {
        return java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxEntries;
            }
        });
    }

    private static <K, V> V cacheGet(Map<K, V> cache, K key) {
        synchronized (cache) {
            return cache.get(key);
        }
    }

    private static <K, V> void cachePut(Map<K, V> cache, K key, V value) {
        synchronized (cache) {
            cache.put(key, value);
        }
    }

    private static <K, V> V cacheGetOrCreate(Map<K, V> cache, K key, Supplier<V> supplier) {
        synchronized (cache) {
            var value = cache.get(key);
            if (value == null) {
                value = supplier.get();
                cache.put(key, value);
            }
            return value;
        }
    }

    private static double round1(double v) { return Math.round(v * 10) / 10.0; }
    private static double round5(double v) { return Math.round(v * 1e5) / 1e5; }

    private record PulseKey(FieldMap field, List<PulseSegment> pulse) {
        @Override
        public boolean equals(Object obj) {
            return obj instanceof PulseKey other && field == other.field && pulse == other.pulse;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(field) + System.identityHashCode(pulse);
        }
    }

    private record SimulationKey(PulseKey pulseKey, long rBits, long zBits) {}

    private record StepKernel(PulseStep step, double dt, double e1, double e2, double timeSeconds) {
        boolean rfOn() { return step.isRfOn(); }
    }

    private record CompiledPulse(FieldMap field, StepKernel[] kernels, double[] stateTimesMicros,
                                 double[][] coilDriveI, double[][] coilDriveQ) {
        int targetStateIndex(double tcMicros) {
            if (tcMicros <= 0) return 0;
            int low = 0;
            int high = stateTimesMicros.length - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                double value = stateTimesMicros[mid];
                if (value < tcMicros) {
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            return Math.min(low, stateTimesMicros.length - 1);
        }
    }

    private record PointContext(CompiledPulse compiledPulse, FieldPoint fieldPoint) {}

    private static final class CursorStateCache {
        private final double initialMx;
        private final double initialMy;
        private final double initialMz;
        private final TreeMap<Integer, MagnetisationState> checkpoints = new TreeMap<>();

        private int stateIndex;
        private double mx;
        private double my;
        private double mz;

        private CursorStateCache(FieldPoint point) {
            this.initialMx = point.mx0();
            this.initialMy = point.my0();
            this.initialMz = point.mz0();
            resetTo(0, new MagnetisationState(initialMx, initialMy, initialMz));
            checkpoints.put(0, new MagnetisationState(initialMx, initialMy, initialMz));
        }

        private synchronized MagnetisationState stateAt(CompiledPulse compiled, FieldPoint point, double tcMicros) {
            int targetIndex = compiled.targetStateIndex(tcMicros);
            if (targetIndex == 0) {
                return checkpoints.get(0);
            }

            if (targetIndex < stateIndex) {
                var checkpoint = checkpoints.floorEntry(targetIndex);
                if (checkpoint == null) {
                    resetTo(0, checkpoints.get(0));
                } else {
                    resetTo(checkpoint.getKey(), checkpoint.getValue());
                }
            }

            while (stateIndex < targetIndex) {
                var next = applyStep(compiled, stateIndex, point, compiled.field(), mx, my, mz);
                mx = next.mx();
                my = next.my();
                mz = next.mz();
                stateIndex++;
                if (stateIndex == targetIndex || stateIndex % CURSOR_CHECKPOINT_INTERVAL == 0) {
                    checkpoints.put(stateIndex, new MagnetisationState(mx, my, mz));
                }
            }

            return new MagnetisationState(mx, my, mz);
        }

        private void resetTo(int index, MagnetisationState state) {
            stateIndex = index;
            mx = state.mx();
            my = state.my();
            mz = state.mz();
        }
    }
}
