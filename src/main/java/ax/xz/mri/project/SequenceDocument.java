package ax.xz.mri.project;

import ax.xz.mri.model.sequence.ClipSequence;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.Segment;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Project-owned editable sequence document.
 *
 * <p>{@link #clipSequence()} holds the authoring model; {@link #segments()} and
 * {@link #pulse()} are its baked per-step arrays ready for the Bloch simulator.
 * {@link #activeSimConfigId()} associates the sequence with the simulation
 * config it was authored against.
 */
public record SequenceDocument(
    ProjectNodeId id,
    String name,
    List<Segment> segments,
    List<PulseSegment> pulse,
    @JsonProperty("clip_sequence") ClipSequence clipSequence,
    @JsonProperty("active_sim_config_id") ProjectNodeId activeSimConfigId
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
