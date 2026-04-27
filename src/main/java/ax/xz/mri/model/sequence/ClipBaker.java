package ax.xz.mri.model.sequence;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Bakes a {@link ClipSequence} into flat {@link Segment}/{@link PulseSegment}
 * arrays the simulator or a hardware device can consume.
 *
 * <p>Walks the time axis at {@code seq.dt()}, evaluates each output channel
 * by summing every clip whose track routes to it (under the active
 * {@link RunContext}), and emits a single {@link PulseSegment} over the whole
 * duration. The per-step {@link PulseStep#controls()} is sized to the
 * channel-slot list and ordered to match it.
 *
 * <p>Two convenience entry points pin the {@code RunContext}:
 * <ul>
 *   <li>{@link #bakeForSimulation(ClipSequence, CircuitDocument)} — uses each
 *       voltage source's channel layout from the circuit, and computes the
 *       {@link PulseStep#rfGate()} hint by inspecting any {@code Modulator}
 *       blocks' referenced sources.</li>
 *   <li>{@link #bakeForHardware(ClipSequence, List)} — given the output
 *       channels declared by a hardware plugin's capabilities, fills the
 *       control array directly. The {@code rfGate} hint is left at zero
 *       because the device handles its own gating.</li>
 * </ul>
 */
public final class ClipBaker {
    public static final double RF_GATE_THRESHOLD = 1e-12;

    private ClipBaker() {}

    public record BakeResult(List<Segment> segments, List<PulseSegment> pulseSegments) {}

    /**
     * Bake for simulation. Channel slots come from the circuit's voltage
     * sources in declaration order; the rfGate hint is derived from
     * {@code Modulator}-fed sources.
     */
    public static BakeResult bakeForSimulation(ClipSequence seq, CircuitDocument circuit) {
        if (seq == null || seq.totalDuration() <= 0 || seq.dt() <= 0 || circuit == null) {
            return new BakeResult(List.of(), List.of());
        }

        var slots = simulationChannelSlots(circuit);
        var rfSlotIndices = simulationRfSlotIndices(circuit, slots);
        return bake(seq, slots, RunContext.SIMULATION, rfSlotIndices);
    }

    /**
     * Bake for hardware. {@code outputChannels} is the ordered slot list
     * declared by the plugin's capabilities — every {@link Track} whose
     * {@link Track#hardwareChannel()} matches one of these contributes.
     */
    public static BakeResult bakeForHardware(ClipSequence seq, List<SequenceChannel> outputChannels) {
        if (seq == null || seq.totalDuration() <= 0 || seq.dt() <= 0 || outputChannels == null) {
            return new BakeResult(List.of(), List.of());
        }
        var slots = outputChannels.toArray(SequenceChannel[]::new);
        return bake(seq, slots, RunContext.HARDWARE, new int[0]);
    }

    /** Backwards-compat alias for the simulator path. */
    public static BakeResult bake(ClipSequence seq, CircuitDocument circuit) {
        return bakeForSimulation(seq, circuit);
    }

    // ── implementation ──────────────────────────────────────────────────────

    private static BakeResult bake(ClipSequence seq, SequenceChannel[] channelSlots,
                                   RunContext context, int[] rfSlotIndices) {
        double dtMicros = seq.dt();
        double dtSeconds = dtMicros * 1e-6;
        int totalSteps = seq.totalSteps();
        int totalChannels = channelSlots.length;
        var clips = seq.clips();

        var tracksById = new HashMap<String, Track>();
        for (var t : seq.tracks()) tracksById.put(t.id(), t);

        var steps = new ArrayList<PulseStep>(totalSteps);
        for (int i = 0; i < totalSteps; i++) {
            double t = i * dtMicros;
            double[] controls = new double[totalChannels];
            for (int k = 0; k < totalChannels; k++) {
                controls[k] = ClipEvaluator.evaluateChannel(
                    clips, tracksById, context, channelSlots[k], t);
            }
            double gate = computeGate(controls, rfSlotIndices);
            steps.add(new PulseStep(controls, gate));
        }

        int nPulse = 0;
        for (var step : steps) if (step.isRfOn()) nPulse++;
        int nFree = totalSteps - nPulse;
        return new BakeResult(
            List.of(new Segment(dtSeconds, nFree, nPulse)),
            List.of(new PulseSegment(List.copyOf(steps)))
        );
    }

    private static double computeGate(double[] controls, int[] rfSlotIndices) {
        if (rfSlotIndices.length == 0) return 0;
        double rfMagSq = 0;
        for (int idx : rfSlotIndices) {
            double v = controls[idx];
            rfMagSq += v * v;
        }
        return rfMagSq > RF_GATE_THRESHOLD * RF_GATE_THRESHOLD ? 1.0 : 0.0;
    }

    private static SequenceChannel[] simulationChannelSlots(CircuitDocument circuit) {
        int total = 0;
        for (var src : circuit.voltageSources()) total += src.channelCount();
        var slots = new SequenceChannel[total];
        int cursor = 0;
        for (var src : circuit.voltageSources()) {
            int count = src.channelCount();
            for (int sub = 0; sub < count; sub++) {
                slots[cursor + sub] = SequenceChannel.of(src.name(), sub);
            }
            cursor += count;
        }
        return slots;
    }

    /**
     * Find control-array indices for any voltage source whose {@code out} port
     * is wired to a {@link CircuitComponent.Modulator}'s I/Q input — those slots'
     * activity drives the {@code rfGate} hint.
     */
    private static int[] simulationRfSlotIndices(CircuitDocument circuit, SequenceChannel[] slots) {
        var rfNames = new java.util.HashSet<String>();
        for (var comp : circuit.components()) {
            if (!(comp instanceof CircuitComponent.Modulator m)) continue;
            var iSrc = CircuitComponent.Modulator.inputSource(m, "in0", circuit);
            var qSrc = CircuitComponent.Modulator.inputSource(m, "in1", circuit);
            if (iSrc != null) rfNames.add(iSrc.name());
            if (qSrc != null) rfNames.add(qSrc.name());
        }
        var indices = new ArrayList<Integer>();
        for (int i = 0; i < slots.length; i++) {
            if (rfNames.contains(slots[i].sourceName())) indices.add(i);
        }
        var arr = new int[indices.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = indices.get(i);
        return arr;
    }

    /** Human-friendly track name for a voltage source's channel. */
    public static String defaultTrackName(CircuitComponent.VoltageSource src, int subIndex) {
        return src.name();
    }

    /**
     * Seed a default track list for a circuit: one track per voltage-source
     * channel slot, in declaration order. The simulation routing is set;
     * hardware routing is left null.
     */
    public static List<Track> defaultTracksFor(CircuitDocument circuit) {
        if (circuit == null) return List.of();
        var out = new ArrayList<Track>();
        for (var src : circuit.voltageSources()) {
            int count = src.channelCount();
            for (int sub = 0; sub < count; sub++) {
                out.add(new Track(SequenceChannel.of(src.name(), sub), defaultTrackName(src, sub)));
            }
        }
        return List.copyOf(out);
    }
}
