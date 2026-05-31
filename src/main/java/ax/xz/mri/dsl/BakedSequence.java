package ax.xz.mri.dsl;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.Segment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * A compiled pulse-sequence ready to hand to an
 * {@link ax.xz.mri.service.procedure.ObservationSource}: the segment / pulse
 * timeline the simulator and hardware paths consume, plus an ordered table of
 * script-side {@link SequenceBuilder#mark marks} the script can use to address
 * windows in the trace (read window, free-precession start, …) without
 * threading those timestamps through side variables.
 *
 * <p>Output of {@link SequenceBuilder#build()}. Immutable.
 */
public record BakedSequence(
    List<Segment> segments,
    List<PulseSegment> pulses,
    double durationSeconds,
    Map<String, Double> marks
) {
    public BakedSequence {
        segments = List.copyOf(segments == null ? List.of() : segments);
        pulses = List.copyOf(pulses == null ? List.of() : pulses);
        marks = Map.copyOf(new LinkedHashMap<>(marks == null ? Map.of() : marks));
        if (segments.size() != pulses.size())
            throw new IllegalArgumentException("segments.size() (" + segments.size()
                + ") must equal pulses.size() (" + pulses.size() + ")");
    }

    /** Empty sequence. */
    public static BakedSequence empty() {
        return new BakedSequence(List.of(), List.of(), 0.0, Map.of());
    }

    /**
     * Time in seconds at which {@code label} was placed via
     * {@link SequenceBuilder#mark(String)}. Throws if the label was never set.
     */
    public double markedTime(String label) {
        var t = marks.get(label);
        if (t == null) throw new IllegalArgumentException(
            "No mark named '" + label + "' on this sequence. Marks present: " + marks.keySet());
        return t;
    }

    /** Lenient lookup — returns empty when the label wasn't marked. */
    public OptionalDouble findMark(String label) {
        var t = marks.get(label);
        return t == null ? OptionalDouble.empty() : OptionalDouble.of(t);
    }

    public boolean isEmpty() { return segments.isEmpty(); }
}
