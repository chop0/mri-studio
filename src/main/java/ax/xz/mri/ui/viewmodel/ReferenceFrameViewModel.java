package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.Trajectory;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.service.simulation.compiled.CompiledSimulation;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Movable reference-frame marker and its cached trajectory.
 *
 * <p>The marker carries a {@link Vec3} position in the lab frame, in metres.
 * Y is no longer pinned to zero — the marker can sit anywhere in the FOV.
 * Code that still thinks in (r, z) mm should convert at the call site:
 * {@code new Vec3(rMm * 1e-3, 0, zMm * 1e-3)}.
 */
public class ReferenceFrameViewModel {
    public final BooleanProperty enabled = new SimpleBooleanProperty(false);
    public final ObjectProperty<Vec3> position = new SimpleObjectProperty<>(Vec3.ZERO);
    public final ObjectProperty<Trajectory> trajectory = new SimpleObjectProperty<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "reference-frame");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong generation = new AtomicLong();
    private Consumer<Throwable> errorSink = ex -> { };

    /** Attach a diagnostics sink (typically MessagesViewModel::logError-bridging). */
    public void setErrorSink(Consumer<Throwable> sink) {
        this.errorSink = sink != null ? sink : ex -> { };
    }

    /** Set the marker to {@code p} (metres) and enable it. */
    public void setReference(Vec3 p) {
        position.set(p != null ? p : Vec3.ZERO);
        enabled.set(true);
    }

    /** Move the (already-enabled) marker to {@code p} (metres). */
    public void moveTo(Vec3 p) {
        position.set(p != null ? p : Vec3.ZERO);
    }

    public void clear() {
        enabled.set(false);
        trajectory.set(null);
        generation.incrementAndGet();
    }

    public void refresh(CompiledSimulation simulation, List<PulseSegment> pulse) {
        long currentGeneration = generation.incrementAndGet();
        if (!enabled.get() || simulation == null || pulse == null) {
            trajectory.set(null);
            return;
        }
        Vec3 here = position.get();
        executor.execute(() -> {
            try {
                var nextTrajectory = simulation.singleSpinTrajectory(here);
                Platform.runLater(() -> {
                    if (currentGeneration != generation.get()) return;
                    trajectory.set(nextTrajectory);
                });
            } catch (Exception ex) {
                errorSink.accept(ex);
                Platform.runLater(() -> {
                    if (currentGeneration != generation.get()) return;
                    trajectory.set(null);
                });
            }
        });
    }

    public void dispose() {
        executor.shutdownNow();
    }
}
