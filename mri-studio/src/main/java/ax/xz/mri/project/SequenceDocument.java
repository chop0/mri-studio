package ax.xz.mri.project;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.Segment;

import java.util.List;

/** Project-owned editable sequence document. */
public record SequenceDocument(
    ProjectNodeId id,
    String name,
    List<Segment> segments,
    List<PulseSegment> pulse
) implements ProjectNode {
    public SequenceDocument {
        segments = List.copyOf(segments);
        pulse = List.copyOf(pulse);
    }

    @Override
    public ProjectNodeKind kind() {
        return ProjectNodeKind.SEQUENCE;
    }
}
