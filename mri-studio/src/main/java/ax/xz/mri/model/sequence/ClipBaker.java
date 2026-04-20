package ax.xz.mri.model.sequence;

import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.FieldDefinition;
import ax.xz.mri.model.simulation.SimulationConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Bakes a {@link ClipSequence} into flat {@link Segment}/{@link PulseSegment} arrays
 * that the MRI simulator can consume.
 *
 * <p>Baking walks the time axis at {@code seq.dt()}, evaluates every clip at each
 * step (overlaps combine additively), and emits a single {@link PulseSegment}
 * spanning the full duration. The per-step {@link PulseStep#controls()} array is
 * sized to {@code config.totalChannelCount()} and ordered to match the
 * {@link SimulationConfig#fields()} list — the same order the simulator uses when
 * interpreting controls through each {@link FieldDefinition}.
 */
public final class ClipBaker {
    private ClipBaker() {}

    /** Result of baking: the segment timing info and its pulse data. */
    public record BakeResult(List<Segment> segments, List<PulseSegment> pulseSegments) {}

    /**
     * Bake a clip sequence into simulator-compatible segments and pulse data.
     *
     * <p>RF gate is evaluated from {@link SequenceChannel#RF_GATE}; steps where
     * gate ≥ 0.5 are considered RF-on.
     *
     * @param seq    the editable clip sequence
     * @param config the simulation config describing the channel layout.
     *               May be {@code null} for a sequence with no active config —
     *               in that case only the RF gate channel is baked and the
     *               control array is empty.
     */
    public static BakeResult bake(ClipSequence seq, SimulationConfig config) {
        if (seq == null || seq.totalDuration() <= 0 || seq.dt() <= 0) {
            return new BakeResult(List.of(), List.of());
        }

        double dtMicros = seq.dt();
        double dtSeconds = dtMicros * 1e-6;
        int totalSteps = seq.totalSteps();
        var clips = seq.clips();

        // Pre-compute the channel layout from the config.
        int totalChannels = config == null ? 0 : config.totalChannelCount();
        var channelSlots = new SequenceChannel[totalChannels];
        if (config != null) {
            int cursor = 0;
            for (var field : config.fields()) {
                int count = field.kind().channelCount();
                for (int sub = 0; sub < count; sub++) {
                    channelSlots[cursor + sub] = SequenceChannel.ofField(field.name(), sub);
                }
                cursor += count;
            }
        }

        var steps = new ArrayList<PulseStep>(totalSteps);
        for (int i = 0; i < totalSteps; i++) {
            double t = i * dtMicros;
            double[] controls = new double[totalChannels];
            for (int k = 0; k < totalChannels; k++) {
                controls[k] = ClipEvaluator.evaluateChannel(clips, channelSlots[k], t);
            }
            double gate = ClipEvaluator.evaluateChannel(clips, SequenceChannel.RF_GATE, t);
            steps.add(new PulseStep(controls, gate));
        }

        int nPulse = 0;
        for (var step : steps) if (step.isRfOn()) nPulse++;
        int nFree = totalSteps - nPulse;

        var segment = new Segment(dtSeconds, nFree, nPulse);
        var pulseSegment = new PulseSegment(List.copyOf(steps));
        return new BakeResult(List.of(segment), List.of(pulseSegment));
    }

    /**
     * Convert a legacy segment-based sequence into a clip sequence, using the
     * supplied config's field order to map control-array indices back to
     * {@link SequenceChannel}s. Each contiguous non-zero run on a channel
     * becomes a {@link ClipShape#CONSTANT} clip.
     *
     * @param segments   legacy segments (provides dt + step counts)
     * @param pulse      per-segment pulse data (the actual control values)
     * @param config     the config whose field layout corresponds to the legacy
     *                   control-array layout; if {@code null}, only the RF gate
     *                   is decoded
     */
    public static ClipSequence fromLegacy(List<Segment> segments, List<PulseSegment> pulse, SimulationConfig config) {
        if (segments.isEmpty() || pulse.isEmpty()) {
            return new ClipSequence(10.0, 300.0, List.of());
        }

        double dt = segments.getFirst().dt() * 1e6; // μs
        double totalDuration = 0;
        for (var seg : segments) totalDuration += seg.totalSteps() * seg.dt() * 1e6;

        // Build the channel layout matching the legacy control array.
        var slots = new ArrayList<SequenceChannel>();
        if (config != null) {
            for (var field : config.fields()) {
                int count = field.kind().channelCount();
                for (int sub = 0; sub < count; sub++) {
                    slots.add(SequenceChannel.ofField(field.name(), sub));
                }
            }
        }
        // The RF gate is always present (decoded from PulseStep.rfGate()).
        slots.add(SequenceChannel.RF_GATE);

        var clips = new ArrayList<SignalClip>();
        for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
            var slot = slots.get(slotIndex);
            boolean isGate = slot.isRfGate();
            int controlIndex = isGate ? -1 : slotIndex;
            double channelTime = 0;
            for (int segIdx = 0; segIdx < segments.size() && segIdx < pulse.size(); segIdx++) {
                var seg = segments.get(segIdx);
                var stepList = pulse.get(segIdx).steps();
                double segDt = seg.dt() * 1e6;
                int stepIdx = 0;
                while (stepIdx < stepList.size()) {
                    double value = isGate ? stepList.get(stepIdx).rfGate() : stepList.get(stepIdx).control(controlIndex);
                    if (Math.abs(value) < 1e-15) { stepIdx++; continue; }
                    int runStart = stepIdx;
                    while (stepIdx < stepList.size()) {
                        double v = isGate ? stepList.get(stepIdx).rfGate() : stepList.get(stepIdx).control(controlIndex);
                        if (Math.abs(v - value) >= 1e-15) break;
                        stepIdx++;
                    }
                    double clipStart = channelTime + runStart * segDt;
                    double clipDuration = (stepIdx - runStart) * segDt;
                    clips.add(new SignalClip(
                        null, slot, ClipShape.CONSTANT,
                        clipStart, clipDuration, value,
                        0, clipDuration,
                        null, null
                    ));
                }
                channelTime += stepList.size() * segDt;
            }
        }

        return new ClipSequence(dt, totalDuration, clips);
    }
}
