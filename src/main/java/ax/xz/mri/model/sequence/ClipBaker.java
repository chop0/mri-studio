package ax.xz.mri.model.sequence;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.simulation.AmplitudeKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Bakes a {@link ClipSequence} into flat {@link Segment}/{@link PulseSegment}
 * arrays the simulator consumes.
 *
 * <p>Walks the time axis at {@code seq.dt()}, evaluates each output channel
 * by summing every clip whose track routes to it, and emits a single
 * {@link PulseSegment} over the whole duration. The per-step
 * {@link PulseStep#controls()} is sized to the circuit's total channel count
 * in voltage-source declaration order.
 *
 * <p>{@link PulseStep#rfGate()} is an optimisation hint derived from any
 * {@link CircuitComponent.Modulator Modulator} block's referenced I/Q
 * sources — a clip is "on" when any I or Q channel it touches is non-zero.
 * The simulator's T/R gating comes from explicit
 * {@link CircuitComponent.SwitchComponent Switch} components in the circuit.
 */
public final class ClipBaker {
    public static final double RF_GATE_THRESHOLD = 1e-12;

    private ClipBaker() {}

    public record BakeResult(List<Segment> segments, List<PulseSegment> pulseSegments) {}

    public static BakeResult bake(ClipSequence seq, CircuitDocument circuit) {
        if (seq == null || seq.totalDuration() <= 0 || seq.dt() <= 0 || circuit == null) {
            return new BakeResult(List.of(), List.of());
        }

        double dtMicros = seq.dt();
        double dtSeconds = dtMicros * 1e-6;
        int totalSteps = seq.totalSteps();
        var clips = seq.clips();

        var tracksById = new HashMap<String, Track>();
        for (var t : seq.tracks()) tracksById.put(t.id(), t);

        int totalChannels = 0;
        for (var src : circuit.voltageSources()) totalChannels += src.channelCount();

        var channelSlots = new SequenceChannel[totalChannels];
        int cursor = 0;
        for (var src : circuit.voltageSources()) {
            int count = src.channelCount();
            for (int sub = 0; sub < count; sub++) {
                channelSlots[cursor + sub] = SequenceChannel.of(src.name(), sub);
            }
            cursor += count;
        }

        // Gather control-indices for every source whose "out" is wired to a
        // Modulator's in0 or in1 port. Those are the "RF envelope" slots
        // whose non-zero activity drives the rfGate hint.
        var rfSlotIndices = new ArrayList<Integer>();
        var rfSourceNames = new java.util.HashSet<String>();
        for (var comp : circuit.components()) {
            if (!(comp instanceof CircuitComponent.Modulator m)) continue;
            var iSrc = CircuitComponent.Modulator.inputSource(m, "in0", circuit);
            var qSrc = CircuitComponent.Modulator.inputSource(m, "in1", circuit);
            if (iSrc != null) rfSourceNames.add(iSrc.name());
            if (qSrc != null) rfSourceNames.add(qSrc.name());
        }
        int offset = 0;
        for (var src : circuit.voltageSources()) {
            if (rfSourceNames.contains(src.name())) {
                for (int sub = 0; sub < src.channelCount(); sub++) {
                    rfSlotIndices.add(offset + sub);
                }
            }
            offset += src.channelCount();
        }

        var steps = new ArrayList<PulseStep>(totalSteps);
        for (int i = 0; i < totalSteps; i++) {
            double t = i * dtMicros;
            double[] controls = new double[totalChannels];
            for (int k = 0; k < totalChannels; k++) {
                controls[k] = ClipEvaluator.evaluateChannel(clips, tracksById, channelSlots[k], t);
            }
            double rfMagSq = 0;
            for (int qIdx : rfSlotIndices) {
                double v = controls[qIdx];
                rfMagSq += v * v;
            }
            double gate = rfMagSq > RF_GATE_THRESHOLD * RF_GATE_THRESHOLD ? 1.0 : 0.0;
            steps.add(new PulseStep(controls, gate));
        }

        int nPulse = 0;
        for (var step : steps) if (step.isRfOn()) nPulse++;
        int nFree = totalSteps - nPulse;

        var segment = new Segment(dtSeconds, nFree, nPulse);
        var pulseSegment = new PulseSegment(List.copyOf(steps));
        return new BakeResult(List.of(segment), List.of(pulseSegment));
    }

    /** Human-friendly track name for a voltage source's channel. */
    public static String defaultTrackName(CircuitComponent.VoltageSource src, int subIndex) {
        return src.name();
    }

    /** One track per voltage-source channel slot, in declaration order. */
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
