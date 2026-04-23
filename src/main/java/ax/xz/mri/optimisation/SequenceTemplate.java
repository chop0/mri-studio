package ax.xz.mri.optimisation;

import java.util.List;

/** Describes the optimised segment layout, including periodic cycle metadata when present. */
public record SequenceTemplate(
    List<ControlSegmentSpec> segments,
    int prefixSegmentCount,
    int cycleSegmentCount,
    int cycleRepeatCount
) {
    public SequenceTemplate {
        segments = List.copyOf(segments);
        if (segments.isEmpty()) throw new IllegalArgumentException("segments must not be empty");
        if (prefixSegmentCount < 0 || prefixSegmentCount > segments.size()) {
            throw new IllegalArgumentException("invalid prefixSegmentCount");
        }
        if (cycleSegmentCount < 0 || cycleRepeatCount < 0) {
            throw new IllegalArgumentException("cycle metadata must be non-negative");
        }
        if (cycleSegmentCount == 0 && cycleRepeatCount != 0) {
            throw new IllegalArgumentException("cycleRepeatCount must be zero when cycleSegmentCount is zero");
        }
        if (cycleSegmentCount > 0) {
            if (prefixSegmentCount + cycleSegmentCount != segments.size()) {
                throw new IllegalArgumentException("periodic templates use reduced segments = prefix + cycle");
            }
            if (cycleRepeatCount == 0) {
                throw new IllegalArgumentException("periodic templates require a positive cycleRepeatCount");
            }
        }
    }

    public static SequenceTemplate finiteTrain(List<ControlSegmentSpec> segments) {
        return new SequenceTemplate(segments, segments.size(), 0, 0);
    }

    public static SequenceTemplate periodicCycle(
        List<ControlSegmentSpec> reducedSegments,
        int prefixSegmentCount,
        int cycleSegmentCount,
        int cycleRepeatCount
    ) {
        return new SequenceTemplate(reducedSegments, prefixSegmentCount, cycleSegmentCount, cycleRepeatCount);
    }

    public boolean periodic() {
        return cycleSegmentCount > 0;
    }

    public int reducedSegmentCount() {
        return segments.size();
    }

    public int expandedSegmentCount() {
        return periodic()
            ? prefixSegmentCount + cycleSegmentCount * cycleRepeatCount
            : segments.size();
    }

    public int flattenedLength() {
        return segments.stream().mapToInt(ControlSegmentSpec::flattenedLength).sum();
    }
}
