package ax.xz.mri.model.sequence;

import java.util.List;

/** All pulse steps belonging to one segment. */
public record PulseSegment(List<PulseStep> steps) {
    public int size() { return steps.size(); }
}
