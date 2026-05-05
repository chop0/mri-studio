package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.hardware.HardwareConfig;
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
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Background runner that pushes a sequence at real hardware via a plugin.
 *
 * <p>Mirrors {@link SequenceSimulationSession}'s shape — three observables
 * for status (running, progress, stale), a debounce-free explicit
 * {@link #run()} entry point. The actual I/O happens on a single
 * {@code hardware-io} executor with the {@link AtomicLong} generation pattern
 * reused from {@link ReferenceFrameViewModel}: a fresh run cancels any
 * in-flight earlier one before its result hits the UI.
 *
 * <p>The session is intentionally <em>not</em> a source of truth for the
 * bound hardware config — it resolves the binding on every run from
 * {@link SequenceEditSession#activeHardwareConfigDoc()}, which goes through
 * the project repository each time. That removes the doc-reference-staleness
 * trap that bit us before (renaming or saving the bound config produced a
 * new doc instance, but a cached reference here would still point at the
 * old one).
 *
 * <p>Hardware runs are deliberately <em>not</em> auto-driven by edits — real
 * devices take time and have side effects, so the user explicitly clicks
 * "Run on hardware". The session does not debounce.
 */
public final class HardwareRunSession {

    public final BooleanProperty running = new SimpleBooleanProperty(false);
    public final DoubleProperty progress = new SimpleDoubleProperty(0);

    public final SequenceEditSession editSession;
    private final StudioSession studioSession;
    private final ExecutorService ioExecutor;
    private final AtomicLong generation = new AtomicLong();
    private boolean disposed;

    // Cached open device — opening a TCP socket per Run-on-Hardware click was
    // adding 50–200 ms of latency before any work happened. Now we open the
    // device on the first run, keep the socket alive between runs, and only
    // re-open if the user re-binds to a different config (different content
    // — including in-place edits like a carrier change). Accessed only from
    // the single-threaded ioExecutor; no extra synchronisation needed.
    private HardwareDevice cachedDevice;
    private HardwareConfig cachedConfig;
    private HardwarePlugin cachedPlugin;

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
        // Resolve the bound config FRESH from the repo. If the user just
        // edited and saved the config, this reflects the new envelope; if
        // they renamed it, this reflects the new name. There is deliberately
        // no cached reference path.
        var config = editSession.activeHardwareConfigDoc();
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

        long t0 = System.nanoTime();
        studioSession.messages.logInfo("Hardware",
            "Running " + steps.size() + " samples on " + plugin.displayName()
                + " (" + config.name() + ")...");

        ioExecutor.execute(() -> {
            try {
                HardwareDevice device = acquireDevice(plugin, config.config());
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
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    var primary = result.probeTraces().primary();
                    int points = primary == null ? 0 : primary.points().size();
                    studioSession.messages.logInfo("Hardware",
                        "Run completed in " + ms + " ms — "
                            + points + " sample(s) on probe " + result.probeTraces().primaryProbeName());
                });
            } catch (HardwareException ex) {
                // The cached device may be in a bad state (closed socket, half
                // sent frame) — drop it so the next run reopens cleanly.
                discardCachedDevice();
                studioSession.messages.logError("Hardware",
                    "Run failed on " + plugin.displayName() + ": " + ex.getMessage(), ex);
                Platform.runLater(() -> {
                    if (currentGen != generation.get()) return;
                    running.set(false);
                    progress.set(0);
                });
            } catch (RuntimeException | Error ex) {
                discardCachedDevice();
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

    /**
     * Return a live, opened {@link HardwareDevice} for the given config —
     * reusing the cached one when its config matches by content, otherwise
     * closing the stale one and opening a fresh device. Always called from
     * the single-threaded {@link #ioExecutor}, so the cache fields don't
     * need synchronisation.
     *
     * <p>Comparison is by {@link Objects#equals} on the config record, not
     * by reference, so an in-place repo update that produces a new
     * {@link HardwareConfigDocument} instance carrying the same values is a
     * cache hit. A genuine envelope change (different carrier, different
     * decimation, different host, …) is a cache miss and triggers reopen
     * — the previously-opened device was talking to a server set up under
     * the old envelope and would be wrong for the new one.
     */
    private HardwareDevice acquireDevice(HardwarePlugin plugin, HardwareConfig config) throws HardwareException {
        if (cachedDevice != null && cachedPlugin == plugin && Objects.equals(cachedConfig, config)) {
            return cachedDevice;
        }
        discardCachedDevice();
        cachedDevice = plugin.open(config);
        cachedPlugin = plugin;
        cachedConfig = config;
        return cachedDevice;
    }

    /** Close the cached device (if any) and clear the cache slots. Errors swallowed — we're tearing down. */
    private void discardCachedDevice() {
        if (cachedDevice != null) {
            try { cachedDevice.close(); } catch (Exception ignored) { /* tearing down */ }
        }
        cachedDevice = null;
        cachedPlugin = null;
        cachedConfig = null;
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
        // Close the cached device on the IO thread so we don't tear down a
        // socket mid-frame from the FX thread. shutdownNow runs after the
        // close task is queued.
        ioExecutor.execute(this::discardCachedDevice);
        ioExecutor.shutdown();
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
