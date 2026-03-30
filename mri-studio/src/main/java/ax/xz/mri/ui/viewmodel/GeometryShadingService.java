package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.service.simulation.BlochSimulator;
import javafx.application.Platform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Background cache for geometry shading snapshots. */
public class GeometryShadingService {
    private static final int RADIAL_SAMPLES = 18;
    private static final double INNER_Z_STEP = 0.5;
    private static final double MID_Z_STEP = 1.0;
    private static final int OUTER_BANDS = 24;

    private static final String SIGNAL_BLOCKED_MESSAGE = "Signal shading is only defined during free precession; showing |M⊥|.";

    private final Executor executor;
    private final Consumer<Runnable> uiDispatcher;
    private final Runnable disposer;
    private final AtomicLong generation = new AtomicLong();

    public GeometryShadingService() {
        this(createExecutor(), Platform::runLater, null);
    }

    GeometryShadingService(Executor executor, Consumer<Runnable> uiDispatcher, Runnable disposer) {
        this.executor = executor;
        this.uiDispatcher = uiDispatcher;
        this.disposer = disposer != null ? disposer : () -> { };
    }

    public void request(
        GeometryViewModel geometry,
        BlochData data,
        List<PulseSegment> pulse,
        double cursorTimeMicros
    ) {
        long currentGeneration = generation.incrementAndGet();
        if (geometry.shadeMode.get() == GeometryViewModel.ShadeMode.OFF || data == null || pulse == null) {
            geometry.shadingSnapshot.set(null);
            geometry.shadingComputing.set(false);
            geometry.signalModeBlocked.set(false);
            geometry.statusMessage.set("");
            return;
        }

        geometry.shadingComputing.set(true);
        executor.execute(() -> {
            try {
                var snapshot = computeSnapshot(data, pulse, cursorTimeMicros, geometry.halfHeight.get(), geometry.shadeMode.get());
                uiDispatcher.accept(() -> {
                    if (currentGeneration != generation.get()) return;
                    geometry.shadingSnapshot.set(snapshot);
                    geometry.signalModeBlocked.set(snapshot != null && snapshot.signalModeBlocked());
                    geometry.statusMessage.set(snapshot != null && snapshot.signalModeBlocked() ? SIGNAL_BLOCKED_MESSAGE : "");
                    geometry.shadingComputing.set(false);
                });
            } catch (Exception ex) {
                uiDispatcher.accept(() -> {
                    if (currentGeneration != generation.get()) return;
                    geometry.shadingSnapshot.set(null);
                    geometry.signalModeBlocked.set(false);
                    geometry.shadingComputing.set(false);
                    geometry.statusMessage.set("Shading failed: " + ex.getMessage());
                });
            }
        });
    }

    public void dispose() {
        disposer.run();
    }

    private GeometryShadingSnapshot computeSnapshot(
        BlochData data,
        List<PulseSegment> pulse,
        double cursorTimeMicros,
        double halfHeight,
        GeometryViewModel.ShadeMode requestedMode
    ) {
        var field = data.field();
        var zSamples = buildZSamples(field.zMm[field.zMm.length - 1]);
        var cells = new GeometryShadingSnapshot.CellSample[RADIAL_SAMPLES][zSamples.size()];

        boolean signalBlocked = requestedMode == GeometryViewModel.ShadeMode.SIGNAL
            && rfGateAtTime(field, pulse, cursorTimeMicros) >= 0.5;
        var effectiveMode = signalBlocked ? GeometryViewModel.ShadeMode.MP : requestedMode;

        double rMax = field.rMm[field.rMm.length - 1];
        double sumMx = 0;
        double sumMy = 0;
        double[][] mx = new double[RADIAL_SAMPLES][zSamples.size()];
        double[][] my = new double[RADIAL_SAMPLES][zSamples.size()];
        double[][] mp = new double[RADIAL_SAMPLES][zSamples.size()];
        double[][] phase = new double[RADIAL_SAMPLES][zSamples.size()];

        for (int radialIndex = 0; radialIndex < RADIAL_SAMPLES; radialIndex++) {
            double rMm = (double) radialIndex / (RADIAL_SAMPLES - 1) * rMax;
            for (int zIndex = 0; zIndex < zSamples.size(); zIndex++) {
                double zMm = zSamples.get(zIndex);
                var state = BlochSimulator.simulateTo(data, rMm, zMm, pulse, cursorTimeMicros);
                mx[radialIndex][zIndex] = state.mx();
                my[radialIndex][zIndex] = state.my();
                mp[radialIndex][zIndex] = state.mPerp();
                phase[radialIndex][zIndex] = state.phaseDeg();
                sumMx += state.mx();
                sumMy += state.my();
            }
        }

        double sumNorm = Math.sqrt(sumMx * sumMx + sumMy * sumMy);
        double ux = sumNorm > 1e-9 ? sumMx / sumNorm : 0;
        double uy = sumNorm > 1e-9 ? sumMy / sumNorm : 0;

        for (int radialIndex = 0; radialIndex < RADIAL_SAMPLES; radialIndex++) {
            for (int zIndex = 0; zIndex < zSamples.size(); zIndex++) {
                double brightness = mp[radialIndex][zIndex];
                if (effectiveMode == GeometryViewModel.ShadeMode.SIGNAL) {
                    brightness = Math.max(0, mx[radialIndex][zIndex] * ux + my[radialIndex][zIndex] * uy);
                }
                cells[radialIndex][zIndex] =
                    new GeometryShadingSnapshot.CellSample(phase[radialIndex][zIndex], brightness);
            }
        }

        return new GeometryShadingSnapshot(zSamples, cells, signalBlocked);
    }

    private static double rfGateAtTime(FieldMap field, List<PulseSegment> pulse, double cursorTimeMicros) {
        double t = 0;
        for (int segmentIndex = 0; segmentIndex < field.segments.size() && segmentIndex < pulse.size(); segmentIndex++) {
            var segment = field.segments.get(segmentIndex);
            for (var step : pulse.get(segmentIndex).steps()) {
                if (t * 1e6 >= cursorTimeMicros) return step.rfGate();
                t += segment.dt();
            }
        }
        return 0;
    }

    private static List<Double> buildZSamples(double zMax) {
        var set = new HashSet<Double>();
        double inner = Math.min(8, zMax);
        double mid = Math.min(20, zMax);
        for (double z = -inner; z <= inner + 1e-6; z += INNER_Z_STEP) set.add(round3(z));
        for (double z = -mid; z <= mid + 1e-6; z += MID_Z_STEP) set.add(round3(z));
        if (zMax > mid) {
            double step = (zMax - mid) / OUTER_BANDS;
            for (int index = 0; index <= OUTER_BANDS; index++) {
                double z = mid + index * step;
                set.add(round3(z));
                set.add(round3(-z));
            }
        }
        set.add(-zMax);
        set.add(zMax);
        var result = new ArrayList<>(set);
        result.sort(Double::compareTo);
        return result;
    }

    private static double round3(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private static ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "geometry-shading");
            thread.setDaemon(true);
            return thread;
        });
    }
}
