package ax.xz.mri.model.sequence;

import java.util.UUID;

/**
 * An arrangement lane in a clip sequence.
 *
 * <p>Each track carries two routings — one for simulation runs and one for
 * hardware runs — both expressed as a {@link SequenceChannel}. The
 * simulation routing also drives the editor's lane display (label, hardware
 * limits, channel colour, etc.); the hardware routing is just the destination
 * picked by {@link ClipBaker} when running on a real device. Either may be
 * {@code null} to indicate "unrouted in that context" — clips on a track with
 * no routing for the active context contribute nothing.
 *
 * <p>Multiple tracks may target the same output for either context — clips
 * sum at evaluation time. This mirrors a DAW's "bus" model: the active config
 * defines the buses, the user arranges lanes however they like.
 *
 * <p>Tracks are user-managed state (added, removed, reordered, renamed,
 * re-routed) and persisted with the {@link ClipSequence}. Clips reference
 * their track by {@link #id()}, not by channel — changing a track's routing
 * moves all its clips in lockstep. Per-track collapse state is view-level
 * (see {@link ax.xz.mri.ui.viewmodel.SequenceEditSession#collapsedTrackIds}).
 */
public record Track(
    String id,
    String name,
    SequenceChannel simChannel,
    SequenceChannel hardwareChannel
) {
    public Track {
        if (id == null) id = UUID.randomUUID().toString();
        if (name == null) name = "";
        // simChannel may be null (unrouted in sim); hardwareChannel may be null (unrouted on hardware).
    }

    /** Convenience: brand-new track with only a sim routing (typical when bound to a sim config). */
    public Track(SequenceChannel simChannel, String name) {
        this(null, name, simChannel, null);
    }

    /**
     * Resolve which channel this track targets in the given run context.
     * Returns {@code null} when the track is unrouted for that context.
     */
    public SequenceChannel channelFor(RunContext context) {
        return context == RunContext.SIMULATION ? simChannel : hardwareChannel;
    }

    public Track withName(String newName) {
        return new Track(id, newName, simChannel, hardwareChannel);
    }

    public Track withSimChannel(SequenceChannel newChannel) {
        return new Track(id, name, newChannel, hardwareChannel);
    }

    public Track withHardwareChannel(SequenceChannel newChannel) {
        return new Track(id, name, simChannel, newChannel);
    }

    public Track withChannelFor(RunContext context, SequenceChannel newChannel) {
        return context == RunContext.SIMULATION
            ? withSimChannel(newChannel)
            : withHardwareChannel(newChannel);
    }

    public Track withNewId() {
        return new Track(UUID.randomUUID().toString(), name, simChannel, hardwareChannel);
    }
}
