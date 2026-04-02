package ax.xz.mri.model.sequence;

import java.util.List;

/**
 * Evaluates the signal value of a {@link SignalClip} at any point in time.
 *
 * <p>All times are in microseconds. The clip's waveform is defined over
 * {@code [clip.startTime(), clip.endTime())}; outside this range the value is zero.
 */
public final class ClipEvaluator {
    private ClipEvaluator() {}

    /**
     * Evaluate the clip's signal at absolute time {@code t} (μs).
     * Returns 0 if {@code t} is outside the clip's time range.
     */
    public static double evaluate(SignalClip clip, double t) {
        if (t < clip.startTime() || t >= clip.endTime() || clip.duration() <= 0) {
            return 0.0;
        }
        // Premiere-style: u is normalised within the full media extent,
        // accounting for mediaOffset. This means trimming the clip's visible
        // range doesn't change the underlying waveform position.
        double u = clip.mediaU(t);
        double shape = evaluateShape(clip, u);
        return clip.amplitude() * shape;
    }

    /**
     * Evaluate the normalised shape function at position {@code u} in [0,1]
     * within the clip's full media extent.
     */
    private static double evaluateShape(SignalClip clip, double u) {
        return switch (clip.shape()) {
            case CONSTANT  -> 1.0;
            case SINC      -> evaluateSinc(clip, u);
            case TRAPEZOID -> evaluateTrapezoid(clip, u);
            case GAUSSIAN  -> evaluateGaussian(clip, u);
            case TRIANGLE  -> evaluateTriangle(clip, u);
            case SPLINE    -> evaluateSpline(clip, u);
        };
    }

    private static double evaluateSinc(SignalClip clip, double u) {
        double windowFactor = clip.param("windowFactor", 1.0);

        // Two-sided bandwidth in Hz — the sinc is defined over the media extent.
        // Trimming the clip (changing visible region) doesn't change the sinc shape.
        double bandwidthHz = clip.param("bandwidthHz", 0);
        if (bandwidthHz <= 0) {
            double legacyBW = clip.param("bandwidth", 3.0);
            bandwidthHz = legacyBW / (clip.mediaDuration() * 1e-6);
        }

        // centerOffset in μs — offset from media centre
        double centerOffset = clip.param("centerOffset", 0);

        // Sinc centre is at u=0.5 in the media extent, plus offset
        double mediaCenter = 0.5 + (centerOffset / clip.mediaDuration());
        double x = (u - mediaCenter) * clip.mediaDuration() * 1e-6 * bandwidthHz * Math.PI;
        double sinc = Math.abs(x) < 1e-12 ? 1.0 : Math.sin(x) / x;

        // Hanning window over the original media extent [0,1].
        // Outside this range (when clip is extended), sinc continues without windowing.
        double window;
        if (u >= 0 && u <= 1) {
            window = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * u * windowFactor));
            window = Math.max(0, Math.min(1, window));
        } else {
            window = 1.0; // no windowing outside original media
        }

        return sinc * window;
    }

    private static double evaluateTrapezoid(SignalClip clip, double u) {
        // Params are in μs; u is in media space [0,1].
        // The trapezoid shape is defined over the media extent.
        double mediaDur = clip.mediaDuration();
        if (mediaDur <= 0) return 0;

        double riseTime = clip.param("riseTime", mediaDur * 0.1);
        double flatTime = clip.param("flatTime", mediaDur * 0.6);

        double riseFrac = riseTime / mediaDur;
        double flatFrac = flatTime / mediaDur;
        double fallStart = riseFrac + flatFrac;

        // Outside [0,1] → zero (extended past the defined shape)
        if (u < 0 || u > 1) return 0;

        if (u < riseFrac) {
            return riseFrac > 0 ? u / riseFrac : 1.0;
        } else if (u < fallStart) {
            return 1.0;
        } else {
            double fallFrac = 1.0 - fallStart;
            return fallFrac > 0 ? (1.0 - u) / fallFrac : 1.0;
        }
    }

    private static double evaluateGaussian(SignalClip clip, double u) {
        // Gaussian extends naturally — no need to clamp u.
        // sigma is in μs, defined relative to media extent.
        double mediaDur = clip.mediaDuration();
        double sigma = clip.param("sigma", mediaDur * 0.2);
        double sigmaFrac = sigma / mediaDur;
        if (sigmaFrac <= 0) return 0;

        double x = (u - 0.5) / sigmaFrac;
        return Math.exp(-0.5 * x * x);
    }

    private static double evaluateTriangle(SignalClip clip, double u) {
        double peakPos = clip.param("peakPosition", 0.5);
        peakPos = Math.max(0, Math.min(1, peakPos));

        // Outside [0,1] → zero
        if (u < 0 || u > 1) return 0;

        if (u <= peakPos) {
            return peakPos > 0 ? u / peakPos : 1.0;
        } else {
            double remaining = 1.0 - peakPos;
            return remaining > 0 ? (1.0 - u) / remaining : 1.0;
        }
    }

    private static double evaluateSpline(SignalClip clip, double u) {
        List<SignalClip.SplinePoint> points = clip.splinePoints();
        if (points.isEmpty()) return 0.0;
        if (points.size() == 1) return points.getFirst().value();

        // Find the surrounding control points
        // Points are sorted by t; clamp u to the point range
        if (u <= points.getFirst().t()) return points.getFirst().value();
        if (u >= points.getLast().t()) return points.getLast().value();

        // Find bracketing interval
        int i = 0;
        while (i < points.size() - 1 && points.get(i + 1).t() <= u) {
            i++;
        }
        if (i >= points.size() - 1) return points.getLast().value();

        // Catmull-Rom spline interpolation using 4 surrounding points
        var p0 = i > 0 ? points.get(i - 1) : points.get(i);
        var p1 = points.get(i);
        var p2 = points.get(i + 1);
        var p3 = i + 2 < points.size() ? points.get(i + 2) : points.get(i + 1);

        double segmentLength = p2.t() - p1.t();
        if (segmentLength <= 0) return p1.value();
        double localT = (u - p1.t()) / segmentLength;

        return catmullRom(p0.value(), p1.value(), p2.value(), p3.value(), localT);
    }

    /** Catmull-Rom interpolation between p1 and p2, using p0 and p3 as tangent guides. */
    private static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * (
            (2 * p1) +
            (-p0 + p2) * t +
            (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 +
            (-p0 + 3 * p1 - 3 * p2 + p3) * t3
        );
    }

    /**
     * Evaluate the total signal on a given channel at time {@code t} by summing
     * all overlapping clips (additive overlap semantics).
     */
    public static double evaluateChannel(List<SignalClip> clips, SignalChannel channel, double t) {
        double sum = 0;
        for (var clip : clips) {
            if (clip.channel() == channel) {
                sum += evaluate(clip, t);
            }
        }
        return sum;
    }
}
