package ax.xz.mri.service.procedure;

import ax.xz.mri.dsl.Script;
import ax.xz.mri.dsl.ScriptContext;
import ax.xz.mri.dsl.ScriptResult;
import ax.xz.mri.dsl.viz.Visualisation;
import ax.xz.mri.service.simulation.compiled.CompiledSimulation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Drives a {@link Script} on a dedicated worker thread, surfaces its UI
 * hooks ({@code status} / {@code progress} / {@code log} / {@code metric}
 * / {@code show}) as {@link Tick} events for the run pane, and assembles
 * the {@code put(...)} / {@code summary(...)} calls into a final
 * {@link ScriptResult}.
 *
 * <p>The script owns its own lifecycle — the harness doesn't loop, doesn't
 * pause, doesn't introspect state. Its only structural responsibilities
 * are: (a) supply a {@link ScriptContext} the script can read; (b) provide
 * a cooperative {@link #stop()} that flips a flag the script polls via
 * {@link ScriptContext#cancelled()} and {@link ScriptContext#checkpoint()};
 * (c) coalesce viz / metric / status emissions into ticks the UI consumes.
 *
 * <p>Threading: ticks are emitted on the harness worker thread. The
 * consumer is responsible for marshalling onto its UI thread if needed
 * (the studio's {@code ProcedureRunPane} does this via
 * {@code Platform.runLater}; the {@code StandaloneProcedureWindow}
 * does the same).
 */
public final class ScriptHarness implements AutoCloseable {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "script-harness");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public CompletableFuture<ScriptResult> run(
        Script script,
        CompiledSimulation simulation,
        ObservationSource source,
        long seed,
        Consumer<Tick> onTick
    ) {
        var future = new CompletableFuture<ScriptResult>();
        stopped.set(false);
        executor.submit(() -> {
            var ctx = new Context(simulation, source, seed, onTick, stopped);
            try {
                onTick.accept(new Tick("starting", 0.0, null, Map.of(), List.of()));
                script.run(ctx);
                ctx.flushPending();
                var result = ctx.result.build();
                onTick.accept(new Tick(
                    result.summary().isEmpty() ? "completed" : result.summary(),
                    1.0, null, Map.of(), List.of()));
                future.complete(result);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                onTick.accept(new Tick("stopped", null, null, Map.of(), List.of()));
                future.complete(ctx.result.build());
            } catch (Throwable t) {
                onTick.accept(new Tick(
                    "failed: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()),
                    null, null, Map.of(), List.of()));
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** Signal the script to stop. Cooperative: takes effect at the script's next {@link ScriptContext#checkpoint()} or {@code cancelled()} check. */
    public void stop() { stopped.set(true); }

    /** True once {@link #stop()} has been called on this run. */
    public boolean isStopped() { return stopped.get(); }

    @Override
    public void close() { executor.shutdownNow(); }

    /**
     * A single coalesced snapshot of script progress: latest status text,
     * latest progress fraction (0..1; {@code null} = "no change since last
     * tick"; {@code Double.NaN} = indeterminate), any log line emitted
     * since the last tick, the metrics buffer drained, and the visualisations
     * drained (keyed by id so re-emissions overwrite in place).
     */
    public record Tick(
        String status,
        Double progress,
        String log,
        Map<String, Double> metrics,
        List<Visualisation> visualisations
    ) {
        public Tick {
            metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
            visualisations = visualisations == null ? List.of() : List.copyOf(visualisations);
        }
    }

    /** Internal {@link ScriptContext} the harness threads into the script. */
    private static final class Context implements ScriptContext {
        private final CompiledSimulation simulation;
        private final ObservationSource source;
        private final long seed;
        private final RandomGenerator rng;
        private final Consumer<Tick> onTick;
        private final AtomicBoolean stopped;

        final ScriptResult.Builder result = new ScriptResult.Builder();

        private String latestStatus = null;
        private Double latestProgress = null;
        private final Map<String, Double> metricsBuffer = new LinkedHashMap<>();
        private final Map<String, Visualisation> vizBuffer = new LinkedHashMap<>();

        Context(CompiledSimulation simulation, ObservationSource source, long seed,
                Consumer<Tick> onTick, AtomicBoolean stopped) {
            this.simulation = simulation;
            this.source = source;
            this.seed = seed;
            this.rng = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(seed);
            this.onTick = onTick;
            this.stopped = stopped;
        }

        @Override public CompiledSimulation simulation() { return simulation; }
        @Override public ObservationSource observationSource() { return source; }
        @Override public RandomGenerator random() { return rng; }
        @Override public long seed() { return seed; }

        @Override
        public synchronized void status(String text) {
            latestStatus = text == null ? "" : text;
            emitIfReady();
        }

        @Override
        public synchronized void progress(double fraction) {
            if (Double.isNaN(fraction)) latestProgress = Double.NaN;
            else latestProgress = Math.max(0.0, Math.min(1.0, fraction));
            emitIfReady();
        }

        @Override
        public synchronized void log(String line) {
            if (line == null || line.isEmpty()) return;
            onTick.accept(new Tick(latestStatus, latestProgress, line,
                drainMetrics(), drainViz()));
        }

        @Override
        public synchronized void show(Visualisation viz) {
            if (viz == null) return;
            vizBuffer.put(viz.id(), viz);
            emitIfReady();
        }

        @Override
        public synchronized void metric(String name, double value) {
            if (name == null) return;
            metricsBuffer.put(name, value);
            emitIfReady();
        }

        @Override
        public synchronized void put(String name, Object value) {
            result.put(name, value);
        }

        @Override
        public synchronized void summary(String summary) {
            result.summary(summary);
        }

        @Override
        public boolean cancelled() {
            return stopped.get() || Thread.currentThread().isInterrupted();
        }

        // Emit a tick whenever the buffers have something the UI cares about.
        // Status / progress changes alone don't ship — they ride on the next
        // viz / metric / log tick to avoid flooding the consumer with empty
        // updates. flushPending() at script-exit ensures the very last
        // status / progress / viz state makes it through.
        private void emitIfReady() {
            if (vizBuffer.isEmpty() && metricsBuffer.isEmpty()) return;
            onTick.accept(new Tick(latestStatus, latestProgress, null,
                drainMetrics(), drainViz()));
        }

        synchronized void flushPending() {
            if (vizBuffer.isEmpty() && metricsBuffer.isEmpty() && latestStatus == null && latestProgress == null) return;
            onTick.accept(new Tick(latestStatus, latestProgress, null,
                drainMetrics(), drainViz()));
        }

        private Map<String, Double> drainMetrics() {
            if (metricsBuffer.isEmpty()) return Map.of();
            var copy = Map.copyOf(metricsBuffer);
            metricsBuffer.clear();
            return copy;
        }

        private List<Visualisation> drainViz() {
            if (vizBuffer.isEmpty()) return List.of();
            var copy = List.copyOf(vizBuffer.values());
            vizBuffer.clear();
            return copy;
        }
    }
}
