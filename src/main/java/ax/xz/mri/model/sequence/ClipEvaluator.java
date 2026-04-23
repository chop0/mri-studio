package ax.xz.mri.model.sequence;

import java.util.List;
import java.util.Map;

/**
 * Aggregation helpers for evaluating the clips of a {@link ClipSequence}.
 *
 * <p>Clips evaluate themselves ({@link SignalClip#evaluate(double)}); this
 * class adds the track→channel routing summation used by the renderer,
 * overview bar, and {@link ClipBaker}. Multiple tracks may route to the same
 * output channel; all their clips contribute additively.
 */
public final class ClipEvaluator {
    private ClipEvaluator() {}

    /**
     * Evaluate the total signal on an output channel at time {@code t}.
     *
     * <p>A clip contributes iff it belongs to a track whose {@code outputChannel}
     * equals {@code channel}.
     */
    public static double evaluateChannel(List<SignalClip> clips, List<Track> tracks,
                                         SequenceChannel channel, double t) {
        double sum = 0;
        for (var clip : clips) {
            var track = findTrackById(tracks, clip.trackId());
            if (track != null && track.outputChannel().equals(channel)) {
                sum += clip.evaluate(t);
            }
        }
        return sum;
    }

    /**
     * Fast variant when the caller already has an id-indexed track map (avoids
     * an O(n) lookup per clip). Used by the baker's inner loop.
     */
    public static double evaluateChannel(List<SignalClip> clips, Map<String, Track> tracksById,
                                         SequenceChannel channel, double t) {
        double sum = 0;
        for (var clip : clips) {
            var track = tracksById.get(clip.trackId());
            if (track != null && track.outputChannel().equals(channel)) {
                sum += clip.evaluate(t);
            }
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
