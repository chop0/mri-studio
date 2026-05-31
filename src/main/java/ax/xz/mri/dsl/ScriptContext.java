package ax.xz.mri.dsl;

import ax.xz.mri.dsl.viz.Visualisation;
import ax.xz.mri.model.probe.Probe;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.model.substance.Substance;
import ax.xz.mri.service.procedure.ObservationSource;
import ax.xz.mri.service.simulation.compiled.CompiledSimulation;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Everything a {@link Script} can ask of its harness — the active simulator
 * (read-only), the observation source it executes pulse sequences against,
 * a seeded RNG, and UI hooks for status / progress / log / viz / metrics
 * / result-bag accumulation / cooperative stop.
 *
 * <p>Every hook is optional. A script that wants to compute silently and
 * return one number can implement {@code run} as a single
 * {@code ctx.put("answer", 42)}; a script that wants to drive a full
 * adaptive UI calls every hook in its main loop.
 *
 * <p>The studio never instantiates a {@code ScriptContext} — the
 * {@link ax.xz.mri.service.procedure.ScriptHarness} builds one and hands
 * it in.
 */
public interface ScriptContext {

    /* ── Backend handles ──────────────────────────────────────────────── */

    /** The compiled simulator. {@code null} when the script is run against hardware only. */
    CompiledSimulation simulation();

    /** Where the script sends pulse sequences. */
    ObservationSource observationSource();

    /** Seeded RNG for script-side randomness. */
    RandomGenerator random();

    /** The seed that produced {@link #random()}, exposed so scripts can re-seed sub-RNGs deterministically. */
    long seed();

    /* ── UI hooks (all optional, all thread-safe) ─────────────────────── */

    /**
     * Persistent text shown beside the progress bar in the run pane (or
     * printed once per change on stdout in standalone mode). Latest call
     * wins. Use this for the "what is the script doing right now" message
     * — e.g. {@code "scanning τ = 1.2 µs"} or {@code "iter 532 / 10000"}.
     */
    void status(String text);

    /**
     * Progress-bar fill in the range {@code [0, 1]}. {@link Double#NaN}
     * marks indeterminate (the bar pulses). Pass anything outside
     * {@code [0,1]} and the harness clamps it.
     */
    void progress(double fraction);

    /** Convenience: {@code progress(done / total)}, with {@code total <= 0} → indeterminate. */
    default void progress(int done, int total) {
        if (total <= 0) progress(Double.NaN);
        else progress(Math.max(0.0, Math.min(1.0, (double) done / total)));
    }

    /**
     * Append a line to the scrolling log pane (or stdout in standalone
     * mode). Use this for low-frequency narrative events; for high-frequency
     * progress, prefer {@link #status} (one-line, replaces in place).
     */
    void log(String line);

    /**
     * Push a typed visualisation (line plot, heatmap, histogram, bars,
     * scalar) into the run pane's Outputs panel. Re-emitting with the same
     * {@link Visualisation#id() id} replaces the previous panel — call this
     * once per iteration with a stable id to get a live-updating chart.
     */
    void show(Visualisation viz);

    /** Stream a custom metric value for the run pane's metrics column. */
    void metric(String name, double value);

    /* ── Result accumulation ──────────────────────────────────────────── */

    /**
     * Stash a named output value (scalar, array, matrix, anything). After
     * the script returns, the harness assembles a result bag from every
     * call to {@link #put}; downstream consumers (tests, UI inspectors,
     * the standalone runner's stdout dump) read it.
     */
    void put(String name, Object value);

    /** One-line human-readable summary shown in the harness's completion notification. */
    void summary(String summary);

    /* ── Cancellation ─────────────────────────────────────────────────── */

    /**
     * {@code true} once the user clicks Stop. Scripts should poll this at
     * least once per iteration of their main loop; long-running inner work
     * should yield to {@link #checkpoint()} which throws on stop instead
     * of returning a flag.
     */
    boolean cancelled();

    /**
     * Cooperative stop point. Throws {@link InterruptedException} if the
     * user has clicked Stop; otherwise returns immediately. Idiomatic at
     * the top of every iteration of a script's main loop, so the harness
     * can stop the script cleanly without it having to thread a
     * {@code cancelled()} check through every branch.
     */
    default void checkpoint() throws InterruptedException {
        if (cancelled()) throw new InterruptedException("script stopped by user");
    }

    /* ── Environment introspection ────────────────────────────────────────
     *
     * Default methods that read directly from {@link #simulation()}, so
     * scripts can write {@code ctx.staticBzAt(...)} instead of juggling
     * Vec3 + FieldSample + null guards. Hardware-only runs
     * ({@code simulation() == null}) return empty / 0 values.
     */

    /** Static {@code Bz} at the supplied lab-frame point, or {@code 0} if no simulator. */
    default double staticBzAt(double xMetres, double yMetres, double zMetres) {
        var sim = simulation();
        if (sim == null) return 0.0;
        return sim.sampleAt(Vec3.of(xMetres, yMetres, zMetres)).staticBz();
    }

    /** Convenience: {@link #staticBzAt(double,double,double)} for {@link Vec3}. */
    default double staticBzAt(Vec3 position) {
        var sim = simulation();
        if (sim == null) return 0.0;
        return sim.sampleAt(position).staticBz();
    }

    /**
     * Static {@code Bz} samples along a 1-D x line at fixed {@code (y, z)}.
     * Returns an {@code xs.length}-sized array. For an NV-only sim where
     * the underlying eigenfields are the ground truth, this gives the exact
     * B-field the script can plot to compare against its posterior estimate.
     */
    default double[] staticBzAlongX(double[] xs, double yMetres, double zMetres) {
        double[] out = new double[xs.length];
        var sim = simulation();
        if (sim == null) return out;
        for (int i = 0; i < xs.length; i++) {
            out[i] = sim.sampleAt(Vec3.of(xs[i], yMetres, zMetres)).staticBz();
        }
        return out;
    }

    /**
     * The substance list of the active simulation. Empty list when the
     * script is run against hardware only ({@link #simulation()} returns
     * {@code null}). Scripts that need a specific substance kind walk
     * this list and pattern-match — e.g.
     * <pre>{@code
     *   for (var s : ctx.substances()) {
     *       if (s instanceof NvEnsemble nv) { ... }
     *   }
     * }</pre>
     */
    default List<Substance> substances() {
        var sim = simulation();
        return sim == null ? List.of() : sim.substances();
    }

    /* ── Sequence / probe convenience ─────────────────────────────────── */

    /**
     * Build a {@link SequenceBuilder} bound to the active simulation's
     * compiled circuit — the simulation-config-level abstraction, not any
     * visual {@link ax.xz.mri.project.SequenceDocument} the user happens
     * to have open. Throws when the script has no simulator attached
     * (hardware-only run with no compiled circuit available).
     */
    default SequenceBuilder newSequence() {
        var sim = simulation();
        if (sim == null) throw new IllegalStateException(
            "Cannot build a sequence: ctx.simulation() is null (script is running hardware-only). "
            + "Hardware scripts must build a SequenceBuilder against a CompiledCircuit explicitly.");
        return SequenceBuilder.forSimulation(sim);
    }

    /**
     * Build a {@link ProbeKey} for the named probe, validating the name
     * against the active simulation when one is attached. Idiomatic at
     * script setup so a typo halts the script immediately rather than
     * producing a silent {@code null} from a downstream trace lookup.
     */
    default ProbeKey probe(String name) {
        var sim = simulation();
        if (sim != null) {
            boolean found = false;
            for (var p : sim.probes()) {
                if (p.name().equals(name)) { found = true; break; }
            }
            if (!found) {
                var avail = sim.probes().stream().map(Probe::name).toList();
                throw new IllegalArgumentException(
                    "No probe named '" + name + "' in active simulation. Available: " + avail);
            }
        }
        return ProbeKey.of(name);
    }

    /**
     * Resolve the named voltage source against the active simulation's
     * compiled circuit, returning a {@link SourceKey} that carries the
     * source's channel offset. Pass the returned key to
     * {@link SequenceBuilder} calls — that lets scripts keep all string
     * matching at the top of {@code run(ctx)} and reference sources via
     * typed handles thereafter.
     *
     * <p>Throws if no simulator is attached (script is hardware-only) or
     * if no source by that name exists in the circuit.
     */
    default SourceKey source(String name) {
        var sim = simulation();
        if (sim == null) throw new IllegalStateException(
            "Cannot resolve source '" + name + "': ctx.simulation() is null "
            + "(script is running hardware-only).");
        for (var src : sim.circuit().sources()) {
            if (src.name().equals(name)) return new SourceKey(name, src.channelOffset());
        }
        var avail = sim.circuit().sources().stream()
            .map(s -> s.name()).toList();
        throw new IllegalArgumentException(
            "No source named '" + name + "' in active simulation's circuit. Available: " + avail);
    }
}
