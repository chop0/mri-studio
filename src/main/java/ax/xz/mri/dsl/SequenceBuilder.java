package ax.xz.mri.dsl;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.service.circuit.CompiledCircuit;
import ax.xz.mri.service.simulation.compiled.CompiledSimulation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent builder for pulse sequences scripts hand to an
 * {@link ax.xz.mri.service.procedure.ObservationSource}. Replaces the ad-hoc
 * "lay out a {@link Segment} + {@link PulseSegment} list + a flat
 * {@code double[totalChannels]} per step" pattern individual procedures used
 * to roll by hand.
 *
 * <p>The builder is bound to a {@link CompiledCircuit} — the simulator-level
 * view of which sources exist and which channel offset each lives at. Scripts
 * don't write source names into builder calls; they look the sources up once
 * via {@link ScriptContext#source(String)} at the top of {@code run(ctx)} and
 * pass the returned {@link SourceKey}s around. That gives scripts: (a) early
 * failure on a typo (the typo throws at {@code ctx.source(...)}, not at the
 * first {@code build()}); (b) zero name-lookup cost per builder call (the
 * {@link SourceKey} already carries its channel offset); (c) a single named
 * handle the script can reuse across every sequence it builds.
 *
 * <p>Scripts run procedures, not edits — a procedure isn't associated with
 * any single {@link ax.xz.mri.project.SequenceDocument} on disk, so the
 * builder takes everything it needs from the simulation config's compiled
 * circuit. Don't add an overload that takes a SequenceDocument; that would
 * pull the editor-layer abstraction into the procedure API.
 *
 * <p>Two primitives + a marker mechanism:
 * <ul>
 *   <li>{@link #hold(double, double, SourceKey, double)} — set the named
 *       channel to {@code value} for {@code duration}, subdivided into steps
 *       of {@code dt}. {@code rfGate = 0} (DC drive: laser, gradient, B0
 *       bias).</li>
 *   <li>{@link #rf(double, double, SourceKey, double)} — same shape with
 *       {@code rfGate = 1} (transverse RF / MW drive: the simulator's
 *       slow-path runs the step and integrates the rotating field properly).</li>
 *   <li>{@link #gap(double)} — one-step segment, all channels zero,
 *       {@code dt = duration}.</li>
 *   <li>{@link #mark(String)} — record the current cursor time under
 *       {@code label}, retrievable via {@link BakedSequence#markedTime}.</li>
 * </ul>
 *
 * <p>Convention: cursor / mark times are in seconds, lab-frame, measured from
 * the start of the sequence. The cursor advances after every segment.
 */
public final class SequenceBuilder {

    private final CompiledCircuit circuit;
    private final int totalChannels;

    private final List<Segment> segments = new ArrayList<>();
    private final List<PulseSegment> pulses = new ArrayList<>();
    private final Map<String, Double> marks = new LinkedHashMap<>();
    private double cursorSeconds = 0.0;

    private SequenceBuilder(CompiledCircuit circuit) {
        if (circuit == null) throw new IllegalArgumentException("CompiledCircuit must not be null");
        this.circuit = circuit;
        this.totalChannels = circuit.totalChannelCount();
    }

    /** Build against a {@link CompiledCircuit} (sim-config-level abstraction). */
    public static SequenceBuilder forCircuit(CompiledCircuit circuit) {
        return new SequenceBuilder(circuit);
    }

    /** Convenience: build against the active simulation's circuit. */
    public static SequenceBuilder forSimulation(CompiledSimulation sim) {
        if (sim == null) throw new IllegalArgumentException(
            "Cannot build a sequence without a simulator — script is hardware-only and "
            + "must construct a SequenceBuilder against a CompiledCircuit explicitly.");
        return new SequenceBuilder(sim.circuit());
    }

    /* ── Primitives ────────────────────────────────────────────────────── */

    /**
     * Hold every supplied {@link SourceKey} at its assigned value for
     * {@code duration} seconds, subdivided into {@code n = round(duration / dt)}
     * steps (clamped to ≥ 1). Sources not in {@code values} are held at zero.
     * {@code rfGate = 0} — use {@link #rf} for transverse RF drive.
     */
    public SequenceBuilder hold(double duration, double dt, Map<SourceKey, Double> values) {
        return appendSegment(duration, dt, values, 0.0);
    }

    /** Single-channel {@link #hold}. */
    public SequenceBuilder hold(double duration, double dt, SourceKey channel, double value) {
        return hold(duration, dt, Map.of(channel, value));
    }

    /**
     * Same shape as {@link #hold}, but flips {@code rfGate = 1} so the
     * simulator's slow-path integrates the transverse RF properly. Idiomatic
     * for MW I/Q drives.
     */
    public SequenceBuilder rf(double duration, double dt, Map<SourceKey, Double> values) {
        return appendSegment(duration, dt, values, 1.0);
    }

    /** Single-channel {@link #rf}. */
    public SequenceBuilder rf(double duration, double dt, SourceKey channel, double value) {
        return rf(duration, dt, Map.of(channel, value));
    }

    /**
     * One-step segment with every channel zero. {@code dt = duration}.
     * The fastest-path "do nothing for this long" primitive — equivalent to
     * {@code hold(duration, duration, Map.of())}.
     */
    public SequenceBuilder gap(double duration) {
        if (!(duration > 0)) throw new IllegalArgumentException("duration must be > 0, got " + duration);
        segments.add(new Segment(duration, 1, 0));
        pulses.add(new PulseSegment(List.of(new PulseStep(new double[totalChannels], 0.0))));
        cursorSeconds += duration;
        return this;
    }

    /**
     * Record the current cursor time (seconds from sequence start) under
     * {@code label}. Recall via {@link BakedSequence#markedTime(String)}.
     */
    public SequenceBuilder mark(String label) {
        if (label == null || label.isBlank())
            throw new IllegalArgumentException("mark label must not be blank");
        marks.put(label, cursorSeconds);
        return this;
    }

    /* ── Accessors ─────────────────────────────────────────────────────── */

    /** Current cursor position, seconds from sequence start. */
    public double cursorSeconds() { return cursorSeconds; }

    /** The compiled circuit this builder resolves channel names against. */
    public CompiledCircuit circuit() { return circuit; }

    /** Build the immutable {@link BakedSequence}. */
    public BakedSequence build() {
        return new BakedSequence(segments, pulses, cursorSeconds, marks);
    }

    /* ── Internals ─────────────────────────────────────────────────────── */

    private SequenceBuilder appendSegment(double duration, double dt,
                                           Map<SourceKey, Double> values, double rfGate) {
        if (!(duration > 0)) throw new IllegalArgumentException("duration must be > 0, got " + duration);
        if (!(dt > 0)) throw new IllegalArgumentException("dt must be > 0, got " + dt);
        int n = Math.max(1, (int) Math.round(duration / dt));
        double[] controls = buildControls(values);
        var steps = new ArrayList<PulseStep>(n);
        for (int i = 0; i < n; i++) steps.add(new PulseStep(controls.clone(), rfGate));
        segments.add(new Segment(dt, n, 0));
        pulses.add(new PulseSegment(steps));
        cursorSeconds += duration;
        return this;
    }

    private double[] buildControls(Map<SourceKey, Double> values) {
        var ctrls = new double[totalChannels];
        if (values == null || values.isEmpty()) return ctrls;
        for (var entry : values.entrySet()) {
            SourceKey key = entry.getKey();
            if (key == null) throw new IllegalArgumentException(
                "null SourceKey in builder.hold / builder.rf");
            int offset = key.channelOffset();
            if (offset >= totalChannels) throw new IllegalArgumentException(
                "SourceKey '" + key.name() + "' channelOffset (" + offset
                + ") is outside this circuit's channel range [0, " + totalChannels
                + "). The key was probably resolved against a different circuit.");
            ctrls[offset] = entry.getValue() == null ? 0.0 : entry.getValue();
        }
        return ctrls;
    }
}
