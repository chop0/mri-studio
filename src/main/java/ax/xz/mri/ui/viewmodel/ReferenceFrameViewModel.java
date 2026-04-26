package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.Trajectory;
import ax.xz.mri.service.simulation.BlochSimulator;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Shared movable reference-frame marker and its cached trajectory. */
public class ReferenceFrameViewModel {
    public final BooleanProperty enabled = new SimpleBooleanProperty(false);
    public final DoubleProperty r = new SimpleDoubleProperty(0);
    public final DoubleProperty z = new SimpleDoubleProperty(0);
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

    public void setReference(double rMm, double zMm) {
        r.set(Math.max(0, rMm));
        z.set(zMm);
        enabled.set(true);
    }

    public void moveTo(double rMm, double zMm) {
        r.set(Math.max(0, rMm));
        z.set(zMm);
    }

    public void clear() {
        enabled.set(false);
        trajectory.set(null);
        generation.incrementAndGet();
    }

    public void refresh(BlochData data, List<PulseSegment> pulse) {
        long currentGeneration = generation.incrementAndGet();
        if (!enabled.get() || data == null || pulse == null) {
            trajectory.set(null);
            return;
        }
        double rMm = r.get();
        double zMm = z.get();
        executor.execute(() -> {
            try {
                var nextTrajectory = BlochSimulator.simulate(data, rMm, zMm, pulse);
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
