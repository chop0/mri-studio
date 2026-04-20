package ax.xz.mri.service.simulation;

import ax.xz.mri.model.field.DynamicFieldMap;
import ax.xz.mri.model.field.FieldInterpolator;
import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.field.FieldPoint;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.simulation.MagnetisationState;
import ax.xz.mri.model.simulation.Trajectory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Core Bloch equation simulator.
 *
 * <p>Integrates in the rotating frame at {@code ω_s = γ · b0Ref}. Each step
 * iterates over the dynamic fields in the {@link FieldMap} and accumulates
 * their per-point eigenfield-scaled contributions into the rotating-frame
 * B-vector, then applies Rodrigues' rotation plus relaxation. Static
 * contributions and Bloch–Siegert corrections from fast fields are baked
 * into {@link FieldPoint#staticBz()} at build time.
 */
public final class BlochSimulator {
    private static final int COMPILED_CACHE_SIZE = 24;
    private static final int POINT_CONTEXT_CACHE_SIZE = 4096;
    /**
     * Upper bound on the number of full trajectories we cache. Each cached
     * trajectory is {@code (stepCount + 1) × 5 × 8} bytes — at 1 µs steps a
     * 100 ms CPMG sequence is ~4 MB per trajectory, so keeping this small
     * matters. Typical usage only needs one entry per visible isochromat
     * anyway; cache misses fall back to full re-simulation, which is still
     * cheap because the CompiledPulse kernels are cached independently.
     */
    private static final int TRAJECTORY_CACHE_SIZE = 32;
    /** Don't cache trajectories bigger than this — recomputing them is cheaper than holding them. */
    private static final int TRAJECTORY_CACHE_STEP_LIMIT = 200_000;
    private static final int CURSOR_CACHE_SIZE = 4096;
    private static final int CURSOR_CHECKPOINT_INTERVAL = 64;

    private static final BlochSimulator DEFAULT = new BlochSimulator(true);

    private final Map<PulseKey, CompiledPulse> compiledCache;
    private final Map<SimulationKey, PointContext> pointContextCache;
    private final Map<SimulationKey, Trajectory> trajectoryCache;
    private final Map<SimulationKey, CursorStateCache> cursorCache;

    public BlochSimulator() {
        this(false);
    }

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

    static void clearCachesForTests() {
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
        // Skip caching very long trajectories to prevent heap blow-up on long CPMG trains.
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
        for (int segmentIndex = 0; segmentIndex < field.segments.size() && segmentIndex < pulse.size(); segmentIndex++) {
            var segment = field.segments.get(segmentIndex);
            double dt = segment.dt();
            double e2 = Math.exp(-dt / field.t2);
            double e1 = Math.exp(-dt / field.t1);
            for (var step : pulse.get(segmentIndex).steps()) {
                kernels[kernelIndex] = new StepKernel(step, dt, e1, e2, timeSeconds);
                timeMicros += dt * 1e6;
                timeSeconds += dt;
                stateTimesMicros[++kernelIndex] = timeMicros;
            }
        }
        return new CompiledPulse(field, kernels, stateTimesMicros);
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

            var next = applyStep(compiled.kernels()[stepIndex], point, field, mx, my, mz);
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

    /**
     * Build the rotating-frame B-vector at this step+point by iterating over
     * dynamic fields, then apply Rodrigues' rotation + relaxation.
     */
    private static MagnetisationState applyStep(StepKernel kernel, FieldPoint point, FieldMap field,
                                                double mx, double my, double mz) {
        double bx = 0, by = 0;
        double bz = point.staticBz();

        var controls = kernel.step().controls();
        var dynamics = field.dynamicFields;
        if (dynamics != null) {
            int n = dynamics.size();
            for (int i = 0; i < n; i++) {
                var df = dynamics.get(i);
                double ex = point.ex()[i];
                double ey = point.ey()[i];
                double ez = point.ez()[i];
                if (df.channelCount == 1) {
                    double amp = controls[df.channelOffset];
                    bx += amp * ex;
                    by += amp * ey;
                    bz += amp * ez;
                } else {
                    double ampI = controls[df.channelOffset];
                    double ampQ = controls[df.channelOffset + 1];
                    // If Δω is non-zero, rotate the (I, Q) pair by Δω·t before applying.
                    if (Math.abs(df.deltaOmega) > 1e-9) {
                        double phase = df.deltaOmega * kernel.timeSeconds();
                        double c = Math.cos(phase);
                        double s = Math.sin(phase);
                        double iRot = ampI * c - ampQ * s;
                        double qRot = ampI * s + ampQ * c;
                        ampI = iRot;
                        ampQ = qRot;
                    }
                    // Quadrature mixing: B⊥ = I·Ẽ − j·Q·Ẽ where Ẽ = Ex + j·Ey.
                    bx += ampI * ex - ampQ * ey;
                    by += ampI * ey + ampQ * ex;
                    bz += ampI * ez;
                }
            }
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

    private static <K, V> V cacheGetOrCreate(Map<K, V> cache, K key, Supplier<V> factory) {
        synchronized (cache) {
            var existing = cache.get(key);
            if (existing != null) return existing;
        }
        var created = factory.get();
        synchronized (cache) {
            var existing = cache.get(key);
            if (existing != null) return existing;
            cache.put(key, created);
            return created;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round5(double v) {
        return Math.round(v * 1e5) / 1e5;
    }

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

    private record SimulationKey(PulseKey pulseKey, long rBits, long zBits) {
    }

    private record StepKernel(PulseStep step, double dt, double e1, double e2, double timeSeconds) {
        boolean rfOn() {
            return step.isRfOn();
        }
    }

    private record CompiledPulse(FieldMap field, StepKernel[] kernels, double[] stateTimesMicros) {
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

    private record PointContext(CompiledPulse compiledPulse, FieldPoint fieldPoint) {
    }

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
                var next = applyStep(compiled.kernels()[stateIndex], point, compiled.field(), mx, my, mz);
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
