package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.MagnetisationState;
import ax.xz.mri.model.simulation.Trajectory;
import ax.xz.mri.service.simulation.BlochSimulator;
import javafx.application.Platform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Background computer for geometry shading snapshots.
 *
 * <p>Samples a (r, z) grid of magnetisation states at the cursor time, shapes
 * them into a shading snapshot, and delivers it on the UI thread.
 *
 * <h2>Density</h2>
 * The grid uses {@value #RADIAL_SAMPLES} radial samples — matching the Phase
 * Map R resolution — so the rendered shading agrees with what Phase Map R and
 * directly-placed isochromats show at off-axis points.
 *
 * <h2>Cost model</h2>
 * <ul>
 *   <li><b>First request per (data, pulse):</b> a parallel pre-warm walks
 *       every grid point end-to-end once, populating the simulator's cursor
 *       cache. This MUST run to completion, independent of the scrub
 *       generation — if it's interrupted by rapid scrubbing, the cursor
 *       cache stays empty and every scrub falls back to a full t=0-to-cursor
 *       walk, which appears as "shading never updates".</li>
 *   <li><b>Subsequent scrubs:</b> each point walks at most one checkpoint
 *       interval (≤ 64 steps) from the nearest cached state. Grid-wide cost
 *       is well under 10 ms on a warm cache.</li>
 *   <li><b>Cancellation:</b> only the snapshot compute is cancellable. It
 *       checks generation between rows and aborts early when a newer request
 *       lands, so continuous dragging doesn't let stale snapshot work queue.</li>
 * </ul>
 */
public class GeometryShadingService {
    private static final int RADIAL_SAMPLES = 20;
    private static final double INNER_Z_STEP = 0.25;
    private static final double MID_Z_STEP = 1.0;
    private static final int OUTER_BANDS = 16;

	private final Executor executor;
    private final Consumer<Runnable> uiDispatcher;
    private final Runnable disposer;
    private final AtomicLong generation = new AtomicLong();
    private final ExecutorService prewarmPool;

    /** Identity tag of the most recently warmed (field, pulse) pair. 0 = never warmed. */
    private volatile int warmedDataTag = 0;

    public GeometryShadingService() {
        this(createExecutor(), Platform::runLater, null);
    }

    GeometryShadingService(Executor executor, Consumer<Runnable> uiDispatcher, Runnable disposer) {
        this.executor = executor;
        this.uiDispatcher = uiDispatcher;
        this.disposer = disposer != null ? disposer : () -> { };
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        this.prewarmPool = Executors.newFixedThreadPool(cores, r -> {
            var t = new Thread(r, "geometry-shading-warm");
            t.setDaemon(true);
            return t;
        });
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
            // Snapshot compute can still be superseded; pre-warm cannot.
            if (currentGeneration != generation.get()) {
                // Even if superseded, finish pre-warming (it's useful for the *newer*
                // request that superseded us). The newer request will overwrite our
                // snapshot anyway, so skip the compute.
                ensureWarmed(data, pulse);
                return;
            }
            try {
                ensureWarmed(data, pulse);

                var snapshot = computeSnapshot(
                    data,
                    pulse,
                    cursorTimeMicros,
                    reference != null && reference.enabled.get() ? reference.trajectory.get() : null,
                    currentGeneration
                );
                if (snapshot == null) return;  // snapshot compute was superseded
                uiDispatcher.accept(() -> {
                    if (currentGeneration != generation.get()) return;
                    geometry.shadingSnapshot.set(snapshot);
                    geometry.statusMessage.set("");
                    geometry.shadingComputing.set(false);
                });
            } catch (OutOfMemoryError oom) {
                uiDispatcher.accept(() -> {
                    if (currentGeneration != generation.get()) return;
                    geometry.shadingSnapshot.set(null);
                    geometry.shadingComputing.set(false);
                    geometry.statusMessage.set("Shading aborted: out of memory");
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
        prewarmPool.shutdownNow();
    }

    /**
     * If this (data, pulse) pair hasn't been pre-warmed yet, do so now.
     * Runs to completion — pre-warm is NOT cancellable by generation advances.
     */
    private void ensureWarmed(BlochData data, List<PulseSegment> pulse) {
        int dataTag = System.identityHashCode(data.field()) ^ System.identityHashCode(pulse);
        if (warmedDataTag == dataTag) return;
        prewarmCursorCache(data, pulse);
        warmedDataTag = dataTag;
    }

    /**
     * Walk every grid point once from t=0 to the end of the sequence, in
     * parallel across CPU cores. This populates BlochSimulator's cursor cache
     * with checkpoints so subsequent {@code simulateTo} calls are cheap.
     *
     * <p>Runs unconditionally to completion — the inner tasks must not early-exit
     * based on {@link #generation}, because skipping the work would leave the
     * cursor cache empty and every future scrub would start from scratch.
     */
    private void prewarmCursorCache(BlochData data, List<PulseSegment> pulse) {
        var field = data.field();
        if (field.segments == null || field.segments.isEmpty()) return;
        double acc = 0;
        for (var seg : field.segments) acc += seg.durationMicros();
        final double totalMicros = acc;
        var zSamples = buildZSamples(field.zMm[field.zMm.length - 1]);
        double rMax = field.rMm[field.rMm.length - 1];

        int total = RADIAL_SAMPLES * zSamples.size();
        var latch = new CountDownLatch(total);
        for (int radialIndex = 0; radialIndex < RADIAL_SAMPLES; radialIndex++) {
            final double rMm = (double) radialIndex / (RADIAL_SAMPLES - 1) * rMax;
            for (int zIndex = 0; zIndex < zSamples.size(); zIndex++) {
                final double zMm = zSamples.get(zIndex);
                prewarmPool.execute(() -> {
                    try {
                        BlochSimulator.simulateTo(data, rMm, zMm, pulse, totalMicros);
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }
        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @return the snapshot, or {@code null} if the computation was superseded by a
     *         newer request and aborted early.
     */
    private GeometryShadingSnapshot computeSnapshot(
        BlochData data,
        List<PulseSegment> pulse,
        double cursorTimeMicros,
        Trajectory referenceTrajectory,
        long myGeneration
    ) {
        var field = data.field();
        var zSamples = buildZSamples(field.zMm[field.zMm.length - 1]);
        double rMax = field.rMm[field.rMm.length - 1];
        int zCount = zSamples.size();

        var cells = new GeometryShadingSnapshot.CellSample[RADIAL_SAMPLES][zCount];

        double sumMx = 0;
        double sumMy = 0;
        double[][] mx = new double[RADIAL_SAMPLES][zCount];
        double[][] my = new double[RADIAL_SAMPLES][zCount];
        double[][] mp = new double[RADIAL_SAMPLES][zCount];
        double[][] phase = new double[RADIAL_SAMPLES][zCount];
        MagnetisationState referenceState = referenceTrajectory != null
            ? referenceTrajectory.stepStateAt(cursorTimeMicros) : null;

        for (int radialIndex = 0; radialIndex < RADIAL_SAMPLES; radialIndex++) {
            if (myGeneration != generation.get()) return null;
            double rMm = (double) radialIndex / (RADIAL_SAMPLES - 1) * rMax;
            for (int zIndex = 0; zIndex < zCount; zIndex++) {
                var state = BlochSimulator.simulateTo(data, rMm, zSamples.get(zIndex), pulse, cursorTimeMicros);
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
            for (int zIndex = 0; zIndex < zCount; zIndex++) {
                double signalProjection = Math.max(0, mx[radialIndex][zIndex] * ux + my[radialIndex][zIndex] * uy);
                cells[radialIndex][zIndex] =
                    new GeometryShadingSnapshot.CellSample(phase[radialIndex][zIndex], mp[radialIndex][zIndex], signalProjection);
            }
        }

        return new GeometryShadingSnapshot(zSamples, cells);
    }

    private static List<Double> buildZSamples(double zMax) {
        var set = new HashSet<Double>();
        double inner = Math.min(12, zMax);
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
