package ax.xz.mri.project;

import ax.xz.mri.model.sequence.ClipSequence;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Project-owned editable sequence document.
 *
 * <p>Holds the canonical authoring state — the {@link #clipSequence()} (tracks
 * + clips + timing), the {@link #activeSimConfigId()} this sequence was
 * authored against, and an optional {@link #preferredHardwareConfigId()} UX
 * hint. The per-step baked outputs (segment list + per-step pulse) are NOT
 * persisted — they're derived from {@code (clipSequence, circuit)} and
 * recomputed by the {@link ax.xz.mri.model.sequence.SequenceBakery} service
 * on demand. Caching keys on the canonical inputs so a circuit edit
 * invalidates the cache automatically.
 */
public record SequenceDocument(
    ProjectNodeId id,
    String name,
    @JsonProperty("clip_sequence") ClipSequence clipSequence,
    @JsonProperty("active_sim_config_id") ProjectNodeId activeSimConfigId,
    @JsonProperty("preferred_hardware_config_id") ProjectNodeId preferredHardwareConfigId
) implements ProjectNode {
    public SequenceDocument {
        if (clipSequence == null) throw new IllegalArgumentException("SequenceDocument.clipSequence must not be null");
    }

    /** Convenience constructor — no preferred hardware config (the typical case). */
    public SequenceDocument(ProjectNodeId id, String name, ClipSequence clipSequence,
                            ProjectNodeId activeSimConfigId) {
        this(id, name, clipSequence, activeSimConfigId, null);
    }

    @Override
    public ProjectNodeKind kind() {
        return ProjectNodeKind.SEQUENCE;
    }

    public SequenceDocument withPreferredHardwareConfigId(ProjectNodeId hwConfigId) {
        return new SequenceDocument(id, name, clipSequence, activeSimConfigId, hwConfigId);
    }
}
