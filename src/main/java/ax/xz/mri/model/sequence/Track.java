package ax.xz.mri.model.sequence;

import java.util.UUID;

/**
 * An arrangement lane in a clip sequence.
 *
 * <p>Each track routes to exactly one {@link SequenceChannel} (one addressable
 * output slot defined by the simulation config). Multiple tracks may route to
 * the same output channel — their clips are summed at evaluation time. This
 * mirrors a DAW's "bus" model: the config defines the buses, the user
 * arranges lanes however they like.
 *
 * <p>Tracks are user-managed state (added, removed, reordered, renamed) and
 * persisted with the {@link ClipSequence}. Clips reference their track by
 * {@link #id()}, not by channel — changing a track's output channel moves all
 * its clips in lockstep.
 */
public record Track(
    String id,
    SequenceChannel outputChannel,
    String name,
    boolean collapsed
) {
    public Track {
        if (id == null) id = UUID.randomUUID().toString();
        if (outputChannel == null)
            throw new IllegalArgumentException("Track.outputChannel must be non-null");
        if (name == null) name = "";
    }

    /** Convenience constructor for a fresh (expanded, auto-id) track. */
    public Track(SequenceChannel outputChannel, String name) {
        this(null, outputChannel, name, false);
    }

    public Track withName(String newName) {
        return new Track(id, outputChannel, newName, collapsed);
    }

    public Track withCollapsed(boolean newCollapsed) {
        return new Track(id, outputChannel, name, newCollapsed);
    }

    public Track withOutputChannel(SequenceChannel newChannel) {
        return new Track(id, newChannel, name, collapsed);
    }

    public Track withNewId() {
        return new Track(UUID.randomUUID().toString(), outputChannel, name, collapsed);
    }
}
