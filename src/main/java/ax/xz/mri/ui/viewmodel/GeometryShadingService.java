package ax.xz.mri.ui.viewmodel;

import module ax.xz.mri;
import module javafx.graphics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Background computer for the geometry-pane shading snapshot.
 *
 * <p>Samples a 2-D grid on the active {@link SlicePlane} at the cursor time,
 * shapes the result into a {@link GeometryShadingSnapshot}, and delivers it
 * on the UI thread. The plane is arbitrary — the service has no idea
 * whether it's sampling a {@code z = 0} half-plane, the equatorial plane,
 * or a tilted slice through an NV array. That's the point of Part 8.
 *
 * <p>Substance gating: when no {@link ax.xz.mri.model.substance.ContinuousMagnetisation
 * continuous-magnetisation substance} is in the FOV, the per-voxel sweep
 * would render only thermal-equilibrium samples (no real data). The
 * service short-circuits and clears the snapshot in that case; the pane
 * paints a status placeholder.
 *
 * <h2>Cost model</h2>
 * Each sample is one {@link CompiledSimulation#singleSpinStateAt} call.
 * The first scrub at a fresh (simulation, pulse) populates the simulator's
 * trajectory LRU; subsequent scrubs across the same simulation re-use the
 * cache. Snapshot compute checks generation between rows so continuous
 * dragging doesn't queue stale work.
 */
public class GeometryShadingService {
    private static final int SAMPLES_U = 24;
    private static final int SAMPLES_V = 32;

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
        CompiledSimulation simulation,
        List<PulseSegment> pulse,
        double cursorTimeMicros,
        ReferenceFrameViewModel reference
    ) {
        long currentGeneration = generation.incrementAndGet();
        if (simulation == null || pulse == null) {
            geometry.shadingSnapshot.set(null);
            geometry.shadingComputing.set(false);
            geometry.statusMessage.set("");
            return;
        }
        // Substance gating — see class doc.
        if (simulation.primaryContinuousMagnetisation() == null) {
            geometry.shadingSnapshot.set(null);
            geometry.shadingComputing.set(false);
            geometry.statusMessage.set("");
            return;
        }

        var plane = geometry.slicePlane.get();
        if (plane == null) {
            geometry.shadingSnapshot.set(null);
            geometry.shadingComputing.set(false);
            geometry.statusMessage.set("");
            return;
        }

        geometry.shadingComputing.set(true);
        executor.execute(() -> {
            if (currentGeneration != generation.get()) return;
            try {
                var snapshot = computeSnapshot(
                    simulation, plane,
                    cursorTimeMicros,
                    reference != null && reference.enabled.get() ? reference.trajectory.get() : null,
                    currentGeneration
                );
                if (snapshot == null) return;
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

    public void clear(GeometryViewModel geometry) {
        generation.incrementAndGet();
        uiDispatcher.accept(() -> {
            geometry.shadingSnapshot.set(null);
            geometry.shadingComputing.set(false);
            geometry.statusMessage.set(null);
        });
    }

    public void dispose() {
        disposer.run();
    }

    private GeometryShadingSnapshot computeSnapshot(
        CompiledSimulation simulation,
        SlicePlane plane,
        double cursorTimeMicros,
        Trajectory referenceTrajectory,
        long myGeneration
    ) {
        // Pick (u, v) sampling extents from the substance bounding box —
        // the half-extent along each plane axis guarantees the plane's
        // intersection with the bounded box is always fully covered.
        // Anything outside still samples cleanly because singleSpinStateAt
        // accepts any 3-D point.
        var fov = substanceHalfExtent(simulation);
        double halfU = halfExtentAlong(plane.u(), fov);
        double halfV = halfExtentAlong(plane.v(), fov);
        var uSamples = buildSamples(-halfU, halfU, SAMPLES_U);
        var vSamples = buildSamples(-halfV, halfV, SAMPLES_V);

        var cells = new GeometryShadingSnapshot.CellSample[SAMPLES_U][SAMPLES_V];

        double sumMx = 0;
        double sumMy = 0;
        double[][] mx = new double[SAMPLES_U][SAMPLES_V];
        double[][] my = new double[SAMPLES_U][SAMPLES_V];
        double[][] mp = new double[SAMPLES_U][SAMPLES_V];
        double[][] phase = new double[SAMPLES_U][SAMPLES_V];
        MagnetisationState referenceState = referenceTrajectory != null
            ? referenceTrajectory.stepStateAt(cursorTimeMicros) : null;

        for (int i = 0; i < SAMPLES_U; i++) {
            if (myGeneration != generation.get()) return null;
            double u = uSamples.get(i);
            for (int j = 0; j < SAMPLES_V; j++) {
                double v = vSamples.get(j);
                Vec3 position = plane.sampleAt(u, v);
                var state = simulation.singleSpinStateAt(position, cursorTimeMicros);
                mx[i][j] = state.mx();
                my[i][j] = state.my();
                mp[i][j] = state.mPerp();
                phase[i][j] = ReferenceFrameUtil.relativePhaseDeg(state.phaseDeg(), referenceState);
                sumMx += state.mx();
                sumMy += state.my();
            }
        }

        double sumNorm = Math.sqrt(sumMx * sumMx + sumMy * sumMy);
        double ux = sumNorm > 1e-9 ? sumMx / sumNorm : 0;
        double uy = sumNorm > 1e-9 ? sumMy / sumNorm : 0;

        for (int i = 0; i < SAMPLES_U; i++) {
            for (int j = 0; j < SAMPLES_V; j++) {
                double signalProjection = Math.max(0, mx[i][j] * ux + my[i][j] * uy);
                cells[i][j] = new GeometryShadingSnapshot.CellSample(
                    phase[i][j], mp[i][j], signalProjection);
            }
        }

        return new GeometryShadingSnapshot(plane, uSamples, vSamples, cells);
    }

    /**
     * Half-extent of the axis-aligned bounding box that contains every
     * substance in the simulation. Falls back to 1 mm cubed when no
     * substance has a non-trivial extent (e.g. an empty schematic) so the
     * shading sweep still gets a non-degenerate sampling box.
     */
    private static Vec3 substanceHalfExtent(CompiledSimulation sim) {
        double hx = 0, hy = 0, hz = 0;
        for (var s : sim.substances()) {
            var h = s.halfExtent();
            hx = Math.max(hx, h.x());
            hy = Math.max(hy, h.y());
            hz = Math.max(hz, h.z());
        }
        return new Vec3(
            hx > 0 ? hx : 1e-3,
            hy > 0 ? hy : 1e-3,
            hz > 0 ? hz : 1e-3);
    }

    /**
     * Maximum {@code |axis · x|} for any {@code x} inside the substance
     * half-extents — the corner of the bounded box farthest from the
     * origin along that axis.
     */
    private static double halfExtentAlong(Vec3 axis, Vec3 fov) {
        return Math.abs(axis.x()) * fov.x()
             + Math.abs(axis.y()) * fov.y()
             + Math.abs(axis.z()) * fov.z();
    }

    private static List<Double> buildSamples(double min, double max, int n) {
        var out = new ArrayList<Double>(n);
        if (n == 1 || min == max) {
            out.add((min + max) / 2);
            return out;
        }
        for (int i = 0; i < n; i++) {
            out.add(min + (max - min) * i / (n - 1));
        }
        return out;
    }

    private static ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "geometry-shading");
            thread.setDaemon(true);
            return thread;
        });
    }
}
