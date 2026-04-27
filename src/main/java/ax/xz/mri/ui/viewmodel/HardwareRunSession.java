package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.hardware.HardwareDevice;
import ax.xz.mri.hardware.HardwareException;
import ax.xz.mri.hardware.HardwarePlugin;
import ax.xz.mri.hardware.HardwarePluginRegistry;
import ax.xz.mri.model.scenario.RunResult;
import ax.xz.mri.model.sequence.ClipBaker;
import ax.xz.mri.model.sequence.ClipSequence;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.project.HardwareConfigDocument;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Background runner that pushes a sequence at real hardware via a plugin.
 *
 * <p>Mirrors {@link SequenceSimulationSession}'s shape — a single property
 * for the active config, three observables for status (running, progress,
 * stale), a debounce-free explicit {@link #run()} entry point. The actual
 * I/O happens on a single {@code hardware-io} executor with the
 * {@link AtomicLong} generation pattern reused from
 * {@link ReferenceFrameViewModel}: a fresh run cancels any in-flight earlier
 * one before its result hits the UI.
 *
 * <p>Hardware runs are deliberately <em>not</em> auto-driven by edits — real
 * devices take time and have side effects, so the user explicitly clicks
 * "Run on hardware". The session does not debounce.
 */
public final class HardwareRunSession {

    public final ObjectProperty<HardwareConfigDocument> activeConfig = new SimpleObjectProperty<>();
    public final BooleanProperty running = new SimpleBooleanProperty(false);
    public final DoubleProperty progress = new SimpleDoubleProperty(0);

    public final SequenceEditSession editSession;
    private final StudioSession studioSession;
    private final ExecutorService ioExecutor;
    private final AtomicLong generation = new AtomicLong();
    private boolean disposed;

    public HardwareRunSession(SequenceEditSession editSession, StudioSession studioSession) {
        this.editSession = editSession;
        this.studioSession = studioSession;
        this.ioExecutor = Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "hardware-io");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Bake the current sequence in {@code RunContext.HARDWARE} and push it
     * through the active hardware config's plugin. The result lands on the
     * analysis panes via {@link StudioSession#loadRunResult}.
     */
    public void run() {
        if (disposed) return;
        var config = activeConfig.get();
        if (config == null || config.config() == null) {
            studioSession.messages.logWarning("Hardware",
                "No hardware config bound. Pick one from the run dropdown first.");
            return;
        }
        var plugin = HardwarePluginRegistry.byId(config.config().pluginId()).orElse(null);
        if (plugin == null) {
            studioSession.messages.logError("Hardware",
                "Plugin not loaded: " + config.config().pluginId(), null);
            return;
        }

        var bake = ClipBaker.bakeForHardware(currentClipSequence(),
            plugin.capabilities().outputChannels());
        var steps = bake.pulseSegments().isEmpty() ? List.<PulseStep>of() : bake.pulseSegments().getFirst().steps();
        if (steps.isEmpty()) {
            studioSession.messages.logWarning("Hardware",
                "Sequence is empty after baking — make sure tracks have hardware routings set.");
            return;
        }

        long currentGen = generation.incrementAndGet();
        running.set(true);
        progress.set(0);

        // Resolve the channel list the plugin advertises so the device gets it 1:1.
        List<SequenceChannel> channels = plugin.capabilities().outputChannels();
        double dtSeconds = editSession.dt.get() * 1e-6;

        ioExecutor.execute(() -> {
            try (HardwareDevice device = plugin.open(config.config())) {
                var result = device.run(dtSeconds, channels, steps,
                    fraction -> Platform.runLater(() -> {
                        if (currentGen == generation.get()) progress.set(fraction);
                    }));
                Platform.runLater(() -> {
                    if (currentGen != generation.get()) return;
                    editSession.lastHardwareTraces.set(result.probeTraces());
                    studioSession.loadRunResult(result);
                    running.set(false);
                    progress.set(1);
                });
            } catch (HardwareException ex) {
                studioSession.messages.logError("Hardware",
                    "Run failed on " + plugin.displayName() + ": " + ex.getMessage(), ex);
                Platform.runLater(() -> {
                    if (currentGen != generation.get()) return;
                    running.set(false);
                    progress.set(0);
                });
            } catch (RuntimeException | Error ex) {
                studioSession.messages.logError("Hardware",
                    "Unexpected error during hardware run: " + ex.getMessage(), ex);
                Platform.runLater(() -> {
                    if (currentGen != generation.get()) return;
                    running.set(false);
                    progress.set(0);
                });
            }
        });
    }

    /** Cancel any pending result delivery without aborting the device call (devices choose their own cancel semantics). */
    public void cancel() {
        generation.incrementAndGet();
        Platform.runLater(() -> {
            running.set(false);
            progress.set(0);
        });
    }

    public void dispose() {
        disposed = true;
        ioExecutor.shutdownNow();
    }

    private ClipSequence currentClipSequence() {
        // Pull the live ClipSequence — same path the sim session uses.
        var doc = editSession.toDocument();
        var clipSeq = doc.clipSequence();
        return clipSeq != null
            ? new ClipSequence(clipSeq.dt(), clipSeq.totalDuration(),
                new ArrayList<>(clipSeq.tracks()), new ArrayList<>(clipSeq.clips()))
            : new ClipSequence(editSession.dt.get(), editSession.totalDuration.get(), List.of(), List.of());
    }
}
