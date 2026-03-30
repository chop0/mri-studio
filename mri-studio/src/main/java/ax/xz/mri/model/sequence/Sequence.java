package ax.xz.mri.model.sequence;

import java.util.List;

/** Central sequence document: ordered list of segments. */
public record Sequence(List<Segment> segments) {

    public double totalTimeMicros() {
        double t = 0;
        for (var seg : segments) t += seg.durationMicros();
        return t;
    }

    /**
     * Returns an array of length {@code segments.size() + 1} giving the cumulative
     * start time (μs) of each segment, with the final entry being the total duration.
     */
    public double[] segmentBounds() {
        var bounds = new double[segments.size() + 1];
        for (int i = 0; i < segments.size(); i++)
            bounds[i + 1] = bounds[i] + segments.get(i).durationMicros();
        return bounds;
    }
}
