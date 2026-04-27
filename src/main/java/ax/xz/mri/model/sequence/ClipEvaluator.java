package ax.xz.mri.model.sequence;

import java.util.List;
import java.util.Map;

/**
 * Aggregation helpers for evaluating the clips of a {@link ClipSequence}.
 *
 * <p>Clips evaluate themselves ({@link SignalClip#evaluate(double)}); this
 * class adds the track→channel routing summation used by the renderer,
 * overview bar, and {@link ClipBaker}. Multiple tracks may route to the same
 * output channel; all their clips contribute additively. Tracks unrouted for
 * the active {@link RunContext} contribute nothing.
 */
public final class ClipEvaluator {
    private ClipEvaluator() {}

    /**
     * Evaluate the total signal on an output channel at time {@code t} in the
     * given context. A clip contributes iff its track's
     * {@link Track#channelFor(RunContext)} equals {@code channel}.
     */
    public static double evaluateChannel(List<SignalClip> clips, List<Track> tracks,
                                         RunContext context, SequenceChannel channel, double t) {
        double sum = 0;
        for (var clip : clips) {
            var track = findTrackById(tracks, clip.trackId());
            if (track == null) continue;
            if (channel.equals(track.channelFor(context))) sum += clip.evaluate(t);
        }
        return sum;
    }

    /**
     * Fast variant when the caller already has an id-indexed track map (avoids
     * an O(n) lookup per clip). Used by the baker's inner loop.
     */
    public static double evaluateChannel(List<SignalClip> clips, Map<String, Track> tracksById,
                                         RunContext context, SequenceChannel channel, double t) {
        double sum = 0;
        for (var clip : clips) {
            var track = tracksById.get(clip.trackId());
            if (track == null) continue;
            if (channel.equals(track.channelFor(context))) sum += clip.evaluate(t);
        }
        return sum;
    }

    /** Sum the contributions of all clips on a single track at time {@code t}. */
    public static double evaluateTrack(List<SignalClip> clips, String trackId, double t) {
        double sum = 0;
        for (var clip : clips) {
            if (trackId.equals(clip.trackId())) sum += clip.evaluate(t);
        }
        return sum;
    }

    /** Single-clip evaluation — convenience wrapper. */
    public static double evaluate(SignalClip clip, double t) {
        return clip.evaluate(t);
    }

    static Track findTrackById(List<Track> tracks, String id) {
        if (id == null) return null;
        for (var t : tracks) if (id.equals(t.id())) return t;
        return null;
    }
}
