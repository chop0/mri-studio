package ax.xz.mri.project;

import ax.xz.mri.model.sequence.ClipSequence;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.Segment;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Project-owned editable sequence document.
 *
 * <p>When {@code clipSequence} is non-null the clip model is authoritative and
 * {@code segments}/{@code pulse} are baked copies for the simulator. Legacy or
 * imported sequences have {@code clipSequence == null} and use the step arrays directly.
 */
public record SequenceDocument(
    ProjectNodeId id,
    String name,
    List<Segment> segments,
    List<PulseSegment> pulse,
    @JsonProperty("clip_sequence") ClipSequence clipSequence
) implements ProjectNode {
    /** Legacy constructor without clip sequence. */
    public SequenceDocument(ProjectNodeId id, String name, List<Segment> segments, List<PulseSegment> pulse) {
        this(id, name, segments, pulse, null);
    }

    public SequenceDocument {
        segments = List.copyOf(segments);
        pulse = List.copyOf(pulse);
    }

    @Override
    public ProjectNodeKind kind() {
        return ProjectNodeKind.SEQUENCE;
    }

    /** Whether this document uses the clip editing model. */
    public boolean hasClipSequence() {
        return clipSequence != null;
    }
}
