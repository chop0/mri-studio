package ax.xz.mri.project;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.Segment;

import java.util.List;

/** Immutable sequence snapshot owned by a capture. */
public record SequenceSnapshotDocument(
    ProjectNodeId id,
    String name,
    ProjectNodeId parentCaptureId,
    List<Segment> segments,
    List<PulseSegment> pulse
) implements ProjectNode {
    public SequenceSnapshotDocument {
        segments = List.copyOf(segments);
        pulse = List.copyOf(pulse);
    }

    @Override
    public ProjectNodeKind kind() {
        return ProjectNodeKind.SEQUENCE_SNAPSHOT;
    }
}
