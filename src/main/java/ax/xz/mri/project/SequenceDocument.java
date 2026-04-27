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
 * config it was authored against. {@link #preferredHardwareConfigId()} is an
 * optional last-used hardware target — purely a UX hint, not a hard binding;
 * sim and hardware configs are independent.
 */
public record SequenceDocument(
    ProjectNodeId id,
    String name,
    List<Segment> segments,
    List<PulseSegment> pulse,
    @JsonProperty("clip_sequence") ClipSequence clipSequence,
    @JsonProperty("active_sim_config_id") ProjectNodeId activeSimConfigId,
    @JsonProperty("preferred_hardware_config_id") ProjectNodeId preferredHardwareConfigId
) implements ProjectNode {
    public SequenceDocument {
        if (clipSequence == null) throw new IllegalArgumentException("SequenceDocument.clipSequence must not be null");
        segments = List.copyOf(segments);
        pulse = List.copyOf(pulse);
    }

    /** Convenience constructor — no preferred hardware config (the typical case). */
    public SequenceDocument(ProjectNodeId id, String name, List<Segment> segments, List<PulseSegment> pulse,
                            ClipSequence clipSequence, ProjectNodeId activeSimConfigId) {
        this(id, name, segments, pulse, clipSequence, activeSimConfigId, null);
    }

    @Override
    public ProjectNodeKind kind() {
        return ProjectNodeKind.SEQUENCE;
    }

    public SequenceDocument withPreferredHardwareConfigId(ProjectNodeId hwConfigId) {
        return new SequenceDocument(id, name, segments, pulse, clipSequence, activeSimConfigId, hwConfigId);
    }
}
