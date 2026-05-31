package ax.xz.mri.ui.sim;

import ax.xz.mri.model.sequence.SequenceBakery;
import ax.xz.mri.model.simulation.SimulationCompiler;
import ax.xz.mri.ui.time.Generation;
import javafx.application.Platform;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runs simulation requests on a single worker thread with single-flight
 * coalescence: at most one task is in flight at any time, and at most one
 * "pending" request is queued behind it.
 *
 * <p>Without coalescence the executor's queue grows by one every debounce
 * fire — a fast clip drag releases at 60 Hz of revision bumps, the dispatch
 * debounce coalesces those into one ~200 ms-spaced submission each, and a
 * 5 s sim run on a 1 s debounce cadence ends up stacking five wasted full
 * grid sweeps in line behind whatever's currently computing. Each run still
 * allocates its own grid + bloch state + per-step Point lists, so the heap
 * grows linearly with queue depth.
 *
 * <p>The fix here drops the queue depth to one: a fresh {@link #submit}
 * arriving while a task is in flight replaces the pending slot rather than
 * queueing a new task, so by the time the worker is free it picks up the
 * <em>latest</em> request and skips every stale one in between. The
 * {@link Generation} token already filters stale results downstream, but
 * filtering after the work finished is a cost-after-the-fact — coalescing
 * up front saves the work outright.
 */
public final class SimRunner implements SimSubmitter {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "sim-runner");
        t.setDaemon(true);
        return t;
    });

    private final SequenceBakery bakery;
    private final SimulationCompiler compiler = new SimulationCompiler();
    private final Generation generation;

    /** Set while the worker is processing a request. Guarded by {@code this}. */
    private boolean inFlight;
    /** The most recently submitted request that hasn't been run yet. Guarded by {@code this}. */
    private final AtomicReference<PendingRequest> pending = new AtomicReference<>();

    public SimRunner(SequenceBakery bakery, Generation generation) {
        this.bakery = bakery;
        this.generation = generation;
    }

    public synchronized void submit(SimRequest request, Consumer<SimResult> onResult, Consumer<Throwable> onError) {
        pending.set(new PendingRequest(request, onResult, onError));
        if (!inFlight) startNext();
    }

    private synchronized void startNext() {
        var next = pending.getAndSet(null);
        if (next == null) {
            inFlight = false;
            return;
        }
        inFlight = true;
        executor.submit(() -> runOne(next));
    }

    private void runOne(PendingRequest p) {
        try {
            var baked = bakery.bake(p.request.sequence(), p.request.repository());
            var segments = baked.segments();
            var pulse = baked.pulseSegments();
            var simulation = compiler.compile(p.request.config(), segments, pulse, p.request.repository());
            var traces = simulation.runMultiProbe();
            var result = new SimResult(simulation, pulse, traces, p.request.generation());
            Platform.runLater(() -> {
                if (generation.isCurrent(p.request.generation())) p.onResult.accept(result);
                startNext();
            });
        } catch (Throwable failure) {
            Platform.runLater(() -> {
                if (generation.isCurrent(p.request.generation())) p.onError.accept(failure);
                startNext();
            });
        }
    }

    public void dispose() {
        executor.shutdownNow();
    }

    private record PendingRequest(SimRequest request, Consumer<SimResult> onResult, Consumer<Throwable> onError) {}
}
