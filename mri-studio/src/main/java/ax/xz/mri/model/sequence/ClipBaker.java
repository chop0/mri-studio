package ax.xz.mri.model.sequence;

import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.FieldDefinition;
import ax.xz.mri.model.simulation.SimulationConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bakes a {@link ClipSequence} into flat {@link Segment}/{@link PulseSegment}
 * arrays that the MRI simulator can consume.
 *
 * <p>Baking walks the time axis at {@code seq.dt()}, evaluates each output
 * channel by summing every clip whose track routes to it, and emits a single
 * {@link PulseSegment} spanning the full duration. Per-step
 * {@link PulseStep#controls()} is sized to
 * {@link SimulationConfig#totalChannelCount()} and ordered to match the
 * {@link SimulationConfig#fields()} list — the same order the simulator uses
 * when interpreting controls through each {@link FieldDefinition}.
 *
 * <p>{@link PulseStep#rfGate()} is auto-computed from the running magnitude of
 * all QUADRATURE field slots; users never edit the gate directly. When
 * {@code sqrt(sum(I² + Q²)) > RF_GATE_THRESHOLD} the step is flagged as RF-on.
 */
public final class ClipBaker {
    /** Amplitude magnitude below which the RF gate is considered off. */
    public static final double RF_GATE_THRESHOLD = 1e-12;

    private ClipBaker() {}

    /** Result of baking: the segment timing info and its pulse data. */
    public record BakeResult(List<Segment> segments, List<PulseSegment> pulseSegments) {}

    /**
     * Bake a clip sequence into simulator-compatible segments and pulse data.
     *
     * @param seq    the editable clip sequence
     * @param config the simulation config describing the channel layout.
     *               May be {@code null} for a sequence with no active config —
     *               in that case an empty (no-step) result is returned.
     */
    public static BakeResult bake(ClipSequence seq, SimulationConfig config) {
        if (seq == null || seq.totalDuration() <= 0 || seq.dt() <= 0 || config == null) {
            return new BakeResult(List.of(), List.of());
        }

        double dtMicros = seq.dt();
        double dtSeconds = dtMicros * 1e-6;
        int totalSteps = seq.totalSteps();
        var clips = seq.clips();

        // Index tracks by id for O(1) lookup in the hot loop.
        var tracksById = new HashMap<String, Track>();
        for (var t : seq.tracks()) tracksById.put(t.id(), t);

        // Pre-compute the channel layout from the config.
        int totalChannels = config.totalChannelCount();
        var channelSlots = new SequenceChannel[totalChannels];
        // Indices of channel slots that belong to a QUADRATURE field — these
        // contribute to RF-gate detection.
        var quadratureSlotIndices = new ArrayList<Integer>();
        int cursor = 0;
        for (var field : config.fields()) {
            int count = field.kind().channelCount();
            for (int sub = 0; sub < count; sub++) {
                channelSlots[cursor + sub] = SequenceChannel.ofField(field.name(), sub);
                if (field.kind() == AmplitudeKind.QUADRATURE) {
                    quadratureSlotIndices.add(cursor + sub);
                }
            }
            cursor += count;
        }

        var steps = new ArrayList<PulseStep>(totalSteps);
        for (int i = 0; i < totalSteps; i++) {
            double t = i * dtMicros;
            double[] controls = new double[totalChannels];
            double rfMagSq = 0;
            for (int k = 0; k < totalChannels; k++) {
                controls[k] = ClipEvaluator.evaluateChannel(clips, tracksById, channelSlots[k], t);
            }
            for (int qIdx : quadratureSlotIndices) {
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

    /**
     * Convert a legacy segment-based sequence into a clip sequence. Legacy
     * sequences encode one channel per control-array slot; we materialise one
     * default {@link Track} per slot and convert each contiguous non-zero run
     * of values on a slot into a {@link ClipShape.Constant} clip on that track.
     *
     * <p>Legacy {@link PulseStep#rfGate()} values are discarded — the new
     * baker auto-derives gate from RF amplitudes, so we never need to round-trip
     * a dedicated gate signal.
     */
    public static ClipSequence fromLegacy(List<Segment> segments, List<PulseSegment> pulse, SimulationConfig config) {
        if (segments.isEmpty() || pulse.isEmpty()) {
            return new ClipSequence(10.0, 300.0, List.of(), List.of());
        }

        double dt = segments.getFirst().dt() * 1e6; // μs
        double totalDuration = 0;
        for (var seg : segments) totalDuration += seg.totalSteps() * seg.dt() * 1e6;

        // Build one track per field-channel slot, in config order.
        var tracks = new ArrayList<Track>();
        var slotTrackIds = new ArrayList<String>();
        if (config != null) {
            for (var field : config.fields()) {
                int count = field.kind().channelCount();
                for (int sub = 0; sub < count; sub++) {
                    var channel = SequenceChannel.ofField(field.name(), sub);
                    var name = defaultTrackName(field, sub);
                    var track = new Track(channel, name);
                    tracks.add(track);
                    slotTrackIds.add(track.id());
                }
            }
        }

        var clips = new ArrayList<SignalClip>();
        for (int slotIndex = 0; slotIndex < slotTrackIds.size(); slotIndex++) {
            String trackId = slotTrackIds.get(slotIndex);
            double channelTime = 0;
            for (int segIdx = 0; segIdx < segments.size() && segIdx < pulse.size(); segIdx++) {
                var seg = segments.get(segIdx);
                var stepList = pulse.get(segIdx).steps();
                double segDt = seg.dt() * 1e6;
                int stepIdx = 0;
                while (stepIdx < stepList.size()) {
                    double value = stepList.get(stepIdx).control(slotIndex);
                    if (Math.abs(value) < 1e-15) { stepIdx++; continue; }
                    int runStart = stepIdx;
                    while (stepIdx < stepList.size()) {
                        double v = stepList.get(stepIdx).control(slotIndex);
                        if (Math.abs(v - value) >= 1e-15) break;
                        stepIdx++;
                    }
                    double clipStart = channelTime + runStart * segDt;
                    double clipDuration = (stepIdx - runStart) * segDt;
                    clips.add(new SignalClip(
                        null, trackId, new ClipShape.Constant(),
                        clipStart, clipDuration, value,
                        0, clipDuration
                    ));
                }
                channelTime += stepList.size() * segDt;
            }
        }

        return new ClipSequence(dt, totalDuration, tracks, clips);
    }

    /** Initial human-readable track name for a field channel (e.g. "B1 · I", "Gradient X"). */
    public static String defaultTrackName(FieldDefinition field, int subIndex) {
        if (field.kind() == AmplitudeKind.QUADRATURE) {
            return field.name() + " \u00b7 " + (subIndex == 0 ? "I" : "Q");
        }
        return field.name();
    }

    /**
     * Seed a default track list for a given config: one track per field-channel
     * slot, in config order. Used when opening a sequence that has a config
     * association but no persisted tracks.
     */
    public static List<Track> defaultTracksFor(SimulationConfig config) {
        if (config == null) return List.of();
        var out = new ArrayList<Track>();
        for (var field : config.fields()) {
            int count = field.kind().channelCount();
            for (int sub = 0; sub < count; sub++) {
                out.add(new Track(SequenceChannel.ofField(field.name(), sub),
                    defaultTrackName(field, sub)));
            }
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unused")
    private static Map<String, Track> indexById(List<Track> tracks) {
        var m = new HashMap<String, Track>();
        for (var t : tracks) m.put(t.id(), t);
        return m;
    }
}
