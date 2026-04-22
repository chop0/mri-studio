package ax.xz.mri.model.sequence;

import java.util.List;

/**
 * Aggregation helpers for evaluating a collection of {@link SignalClip}s.
 *
 * <p>Individual clips evaluate themselves ({@link SignalClip#evaluate(double)});
 * this class provides the per-channel summation used by the renderer, overview
 * bar and {@link ClipBaker}.
 */
public final class ClipEvaluator {
    private ClipEvaluator() {}

    /**
     * Evaluate the total signal on a given channel at time {@code t} by
     * summing all clips currently placed on that channel (overlapping clips
     * combine additively).
     */
    public static double evaluateChannel(List<SignalClip> clips, SequenceChannel channel, double t) {
        double sum = 0;
        for (var clip : clips) {
            if (clip.channel().equals(channel)) sum += clip.evaluate(t);
        }
        return sum;
    }

    /** Single-clip evaluation — convenience wrapper. */
    public static double evaluate(SignalClip clip, double t) {
        return clip.evaluate(t);
    }
}
