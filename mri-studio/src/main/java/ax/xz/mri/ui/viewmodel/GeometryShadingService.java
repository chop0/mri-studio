package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.MagnetisationState;
import ax.xz.mri.model.simulation.Trajectory;
import ax.xz.mri.service.simulation.BlochSimulator;
import javafx.application.Platform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Background cache for geometry shading snapshots. */
public class GeometryShadingService {
    private static final int RADIAL_SAMPLES = 18;
    private static final double INNER_Z_STEP = 0.1;
    private static final double MID_Z_STEP = 1.0;
    private static final int OUTER_BANDS = 24;
    private static final int GRID_CACHE_SIZE = 8;

	private final BlochSimulator  blochSimulator;
    private final Executor executor;
    private final Consumer<Runnable> uiDispatcher;
    private final Runnable disposer;
    private final AtomicLong generation = new AtomicLong();
    private final Map<GridKey, TrajectoryGrid> trajectoryGridCache = synchronizedLruCache(GRID_CACHE_SIZE);

    public GeometryShadingService() {
        this(new BlochSimulator(), createExecutor(), Platform::runLater, null);
    }

    GeometryShadingService(BlochSimulator blochSimulator, Executor executor, Consumer<Runnable> uiDispatcher, Runnable disposer) {
		this.blochSimulator = blochSimulator;
        this.executor = executor;
        this.uiDispatcher = uiDispatcher;
        this.disposer = disposer != null ? disposer : () -> { };
    }

    public void request(
        GeometryViewModel geometry,
        BlochData data,
        List<PulseSegment> pulse,
        double cursorTimeMicros,
        ReferenceFrameViewModel reference
    ) {
        long currentGeneration = generation.incrementAndGet();
        if (data == null || pulse == null) {
            geometry.shadingSnapshot.set(null);
            geometry.shadingComputing.set(false);
            geometry.statusMessage.set("");
            return;
        }

        geometry.shadingComputing.set(true);
        executor.execute(() -> {
            try {
                var snapshot = computeSnapshot(
                    data,
                    pulse,
                    cursorTimeMicros,
                    reference != null && reference.enabled.get() ? reference.trajectory.get() : null
                );
                uiDispatcher.accept(() -> {
                    if (currentGeneration != generation.get()) return;
                    geometry.shadingSnapshot.set(snapshot);
                    geometry.statusMessage.set("");
                    geometry.shadingComputing.set(false);
                });
            } catch (Exception ex) {
                uiDispatcher.accept(() -> {
                    if (currentGeneration != generation.get()) return;
                    geometry.shadingSnapshot.set(null);
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
        Trajectory referenceTrajectory
    ) {
        var field = data.field();
        var grid = trajectoryGridFor(data, pulse);
        var cells = new GeometryShadingSnapshot.CellSample[RADIAL_SAMPLES][grid.zSamples().size()];

        double sumMx = 0;
        double sumMy = 0;
        double[][] mx = new double[RADIAL_SAMPLES][grid.zSamples().size()];
        double[][] my = new double[RADIAL_SAMPLES][grid.zSamples().size()];
        double[][] mp = new double[RADIAL_SAMPLES][grid.zSamples().size()];
        double[][] phase = new double[RADIAL_SAMPLES][grid.zSamples().size()];
        MagnetisationState referenceState = referenceTrajectory != null ? referenceTrajectory.stepStateAt(cursorTimeMicros) : null;

        for (int radialIndex = 0; radialIndex < RADIAL_SAMPLES; radialIndex++) {
            for (int zIndex = 0; zIndex < grid.zSamples().size(); zIndex++) {
                var state = grid.trajectories()[radialIndex][zIndex].stepStateAt(cursorTimeMicros);
                mx[radialIndex][zIndex] = state.mx();
                my[radialIndex][zIndex] = state.my();
                mp[radialIndex][zIndex] = state.mPerp();
                phase[radialIndex][zIndex] = ReferenceFrameUtil.relativePhaseDeg(state.phaseDeg(), referenceState);
                sumMx += state.mx();
                sumMy += state.my();
            }
        }

        double sumNorm = Math.sqrt(sumMx * sumMx + sumMy * sumMy);
        double ux = sumNorm > 1e-9 ? sumMx / sumNorm : 0;
        double uy = sumNorm > 1e-9 ? sumMy / sumNorm : 0;

        for (int radialIndex = 0; radialIndex < RADIAL_SAMPLES; radialIndex++) {
            for (int zIndex = 0; zIndex < grid.zSamples().size(); zIndex++) {
                double signalProjection = Math.max(0, mx[radialIndex][zIndex] * ux + my[radialIndex][zIndex] * uy);
                cells[radialIndex][zIndex] =
                    new GeometryShadingSnapshot.CellSample(phase[radialIndex][zIndex], mp[radialIndex][zIndex], signalProjection);
            }
        }

        return new GeometryShadingSnapshot(grid.zSamples(), cells);
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

    private TrajectoryGrid trajectoryGridFor(BlochData data, List<PulseSegment> pulse) {
        var field = data.field();
        var key = new GridKey(field, pulse);
        synchronized (trajectoryGridCache) {
            var existing = trajectoryGridCache.get(key);
            if (existing != null) return existing;
        }

        var zSamples = buildZSamples(field.zMm[field.zMm.length - 1]);
        double rMax = field.rMm[field.rMm.length - 1];
        var trajectories = new Trajectory[RADIAL_SAMPLES][zSamples.size()];
        for (int radialIndex = 0; radialIndex < RADIAL_SAMPLES; radialIndex++) {
            double rMm = (double) radialIndex / (RADIAL_SAMPLES - 1) * rMax;
            for (int zIndex = 0; zIndex < zSamples.size(); zIndex++) {
                trajectories[radialIndex][zIndex] = BlochSimulator.simulate(data, rMm, zSamples.get(zIndex), pulse);
            }
        }
        var created = new TrajectoryGrid(zSamples, trajectories);
        synchronized (trajectoryGridCache) {
            var existing = trajectoryGridCache.get(key);
            if (existing != null) return existing;
            trajectoryGridCache.put(key, created);
            return created;
        }
    }

    private static <K, V> Map<K, V> synchronizedLruCache(int maxEntries) {
        return java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxEntries;
            }
        });
    }

    private record GridKey(FieldMap field, List<PulseSegment> pulse) {
        @Override
        public boolean equals(Object obj) {
            return obj instanceof GridKey other && field == other.field && pulse == other.pulse;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(field) + System.identityHashCode(pulse);
        }
    }

    private record TrajectoryGrid(List<Double> zSamples, Trajectory[][] trajectories) {
    }
}
