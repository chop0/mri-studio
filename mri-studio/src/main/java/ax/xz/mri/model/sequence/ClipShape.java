package ax.xz.mri.model.sequence;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Waveform generator for a {@link SignalClip}. Every concrete shape is a record
 * that owns its own typed parameters and knows how to evaluate itself, so the
 * evaluator is trivially polymorphic — no shared parameter map, no string keys.
 *
 * <h3>Authoring</h3>
 * <p>Adding a new shape is three things: a new record implementing this
 * interface, a {@link ClipKind} enum entry, and a case in
 * {@link ClipKind#defaultFor(double)}.
 *
 * <h3>Semantics</h3>
 * <p>All shapes are evaluated over a normalised media position {@code u ∈ [0, 1]}
 * that corresponds to a position within the clip's full <em>media extent</em>.
 * The clip handles Premiere-style trimming by offsetting that extent — the
 * shape itself is blind to the visible window.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ClipShape.Constant.class,  name = "CONSTANT"),
    @JsonSubTypes.Type(value = ClipShape.Sinc.class,      name = "SINC"),
    @JsonSubTypes.Type(value = ClipShape.Trapezoid.class, name = "TRAPEZOID"),
    @JsonSubTypes.Type(value = ClipShape.Gaussian.class,  name = "GAUSSIAN"),
    @JsonSubTypes.Type(value = ClipShape.Triangle.class,  name = "TRIANGLE"),
    @JsonSubTypes.Type(value = ClipShape.Sine.class,      name = "SINE"),
    @JsonSubTypes.Type(value = ClipShape.Spline.class,    name = "SPLINE"),
})
public sealed interface ClipShape
    permits ClipShape.Constant, ClipShape.Sinc, ClipShape.Trapezoid,
            ClipShape.Gaussian, ClipShape.Triangle, ClipShape.Sine, ClipShape.Spline {

    /** Identity of this shape, for iteration and tool-palette templating. */
    ClipKind kind();

    /**
     * Evaluate the normalised shape at position {@code u ∈ [0, 1]} within the
     * clip's full media extent. Some shapes (SINC, SINE) need to know the media
     * duration in microseconds to compute real-time quantities.
     */
    double evaluate(double u, double mediaDurationMicros);

    /**
     * Return a new shape suitable for one half of a {@link SignalClip#split split},
     * adjusting any internal parameters (e.g. sinc centring, spline control
     * points) so each half retains its original behaviour within its new window.
     *
     * <p>Default: shape is unaffected by splits.
     *
     * @param originalDuration the original clip duration (μs)
     * @param splitU           the split point as a fraction of the original duration
     * @param leftHalf         {@code true} if building the left piece, {@code false} for the right
     */
    default ClipShape onSplit(double originalDuration, double splitU, boolean leftHalf) {
        return this;
    }

    /** Short, human-readable name for UI surfaces. */
    default String displayName() { return kind().displayName(); }

    /** Sentence fragment describing what this shape represents. */
    default String description() { return kind().description(); }

    // ══════════════════════════════════════════════════════════════════════════
    // Shape implementations
    // ══════════════════════════════════════════════════════════════════════════

    /** Flat amplitude — always returns 1.0. */
    record Constant() implements ClipShape {
        @Override public ClipKind kind() { return ClipKind.CONSTANT; }
        @Override public double evaluate(double u, double mediaDurationMicros) { return 1.0; }
    }

    /**
     * Sinc pulse with a Hanning window applied across the original media extent.
     *
     * @param bandwidthHz   two-sided bandwidth (Hz)
     * @param centerOffset  offset of the sinc centre from media-middle (μs)
     * @param windowFactor  Hanning window shape factor — 1.0 = single period across media
     */
    record Sinc(double bandwidthHz, double centerOffset, double windowFactor) implements ClipShape {
        /** Canonical defaults for a newly-placed sinc clip. */
        public static Sinc defaults() { return new Sinc(4000.0, 0.0, 1.0); }

        @Override public ClipKind kind() { return ClipKind.SINC; }

        @Override public double evaluate(double u, double mediaDurationMicros) {
            double mediaCenter = 0.5 + (centerOffset / mediaDurationMicros);
            double x = (u - mediaCenter) * mediaDurationMicros * 1e-6 * bandwidthHz * Math.PI;
            double sinc = Math.abs(x) < 1e-12 ? 1.0 : Math.sin(x) / x;
            double window;
            if (u >= 0 && u <= 1) {
                double w = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * u * windowFactor));
                window = Math.max(0, Math.min(1, w));
            } else {
                window = 1.0; // sinc continues unwindowed outside its authored media
            }
            return sinc * window;
        }

        @Override public ClipShape onSplit(double originalDuration, double splitU, boolean leftHalf) {
            double originalCenter = originalDuration / 2.0 + centerOffset;
            if (leftHalf) {
                double leftDuration = splitU * originalDuration;
                return new Sinc(bandwidthHz, originalCenter - leftDuration / 2.0, windowFactor);
            }
            double leftDuration = splitU * originalDuration;
            double rightDuration = originalDuration - leftDuration;
            double rightLocalCenter = originalCenter - leftDuration;
            return new Sinc(bandwidthHz, rightLocalCenter - rightDuration / 2.0, windowFactor);
        }
    }

    /**
     * Linear ramp up, flat top, linear ramp down — timed relative to the media
     * extent. Outside the authored extent the trapezoid is zero.
     *
     * @param riseTime  ramp-up duration (μs)
     * @param flatTime  flat-top duration (μs)
     */
    record Trapezoid(double riseTime, double flatTime) implements ClipShape {
        /** Defaults scaled to a given media duration (rise = 15 %, flat = 50 %). */
        public static Trapezoid defaultFor(double mediaDurationMicros) {
            return new Trapezoid(mediaDurationMicros * 0.15, mediaDurationMicros * 0.50);
        }

        @Override public ClipKind kind() { return ClipKind.TRAPEZOID; }

        @Override public double evaluate(double u, double mediaDurationMicros) {
            if (mediaDurationMicros <= 0 || u < 0 || u > 1) return 0;
            double riseFrac = riseTime / mediaDurationMicros;
            double flatFrac = flatTime / mediaDurationMicros;
            double fallStart = riseFrac + flatFrac;
            if (u < riseFrac) return riseFrac > 0 ? u / riseFrac : 1.0;
            if (u < fallStart) return 1.0;
            double fallFrac = 1.0 - fallStart;
            return fallFrac > 0 ? (1.0 - u) / fallFrac : 1.0;
        }
    }

    /**
     * Gaussian envelope centred at {@code u = 0.5}. Extends naturally outside
     * the authored media — the sigma is defined relative to the media extent
     * and remains fixed when the visible window is trimmed.
     *
     * @param sigma  Gaussian σ (μs)
     */
    record Gaussian(double sigma) implements ClipShape {
        public static Gaussian defaultFor(double mediaDurationMicros) {
            return new Gaussian(mediaDurationMicros * 0.20);
        }

        @Override public ClipKind kind() { return ClipKind.GAUSSIAN; }

        @Override public double evaluate(double u, double mediaDurationMicros) {
            double sigmaFrac = mediaDurationMicros > 0 ? sigma / mediaDurationMicros : 0;
            if (sigmaFrac <= 0) return 0;
            double x = (u - 0.5) / sigmaFrac;
            return Math.exp(-0.5 * x * x);
        }
    }

    /**
     * Linear ramp to a peak, then linear ramp down.
     *
     * @param peakPosition  position of the peak within the media, in [0, 1]
     */
    record Triangle(double peakPosition) implements ClipShape {
        public static Triangle defaults() { return new Triangle(0.5); }

        @Override public ClipKind kind() { return ClipKind.TRIANGLE; }

        @Override public double evaluate(double u, double mediaDurationMicros) {
            if (u < 0 || u > 1) return 0;
            double peak = Math.max(0, Math.min(1, peakPosition));
            if (u <= peak) return peak > 0 ? u / peak : 1.0;
            double remaining = 1.0 - peak;
            return remaining > 0 ? (1.0 - u) / remaining : 1.0;
        }
    }

    /**
     * Sinusoidal oscillation over the media extent.
     *
     * <p>Two mutually-exclusive parameterisations:
     * <ul>
     *   <li>{@code cycles > 0} — exactly N cycles fit within the media extent,
     *       independent of duration.</li>
     *   <li>{@code cycles == 0} — {@code frequencyHz} controls the real-world
     *       frequency; the number of cycles scales with the media duration.</li>
     * </ul>
     *
     * @param frequencyHz  real-world frequency in Hz (used when {@code cycles == 0})
     * @param phase        radian phase offset
     * @param cycles       cycle count over media extent; {@code ≤ 0} routes through frequencyHz
     */
    record Sine(double frequencyHz, double phase, double cycles) implements ClipShape {
        public static Sine defaults() { return new Sine(1000.0, 0.0, 0.0); }

        @Override public ClipKind kind() { return ClipKind.SINE; }

        @Override public double evaluate(double u, double mediaDurationMicros) {
            double theta;
            if (cycles > 0) {
                theta = 2.0 * Math.PI * cycles * u + phase;
            } else {
                double tSeconds = u * mediaDurationMicros * 1e-6;
                theta = 2.0 * Math.PI * frequencyHz * tSeconds + phase;
            }
            return Math.sin(theta);
        }
    }

    /**
     * Cubic Catmull–Rom spline through a sorted list of control points. Each
     * point carries a normalised time {@code t ∈ [0, 1]} within the media
     * extent and a vertical value in {@code [-1, 1]} (scaled by clip amplitude
     * at evaluation time).
     */
    record Spline(List<Point> points) implements ClipShape {
        /** A single control point: normalised time + signed value. */
        public record Point(double t, double value) {}

        public Spline {
            points = points == null ? List.of() : List.copyOf(points);
        }

        /** Canonical 3-point defaults (ramp up to peak, ramp down). */
        public static Spline defaults() {
            return new Spline(List.of(
                new Point(0.0, 0.0),
                new Point(0.5, 1.0),
                new Point(1.0, 0.0)
            ));
        }

        @Override public ClipKind kind() { return ClipKind.SPLINE; }

        public Spline withPoints(List<Point> points) { return new Spline(points); }

        @Override public double evaluate(double u, double mediaDurationMicros) {
            if (points.isEmpty()) return 0.0;
            if (points.size() == 1) return points.getFirst().value();
            if (u <= points.getFirst().t()) return points.getFirst().value();
            if (u >= points.getLast().t()) return points.getLast().value();

            int i = 0;
            while (i < points.size() - 1 && points.get(i + 1).t() <= u) i++;
            if (i >= points.size() - 1) return points.getLast().value();

            var p0 = i > 0 ? points.get(i - 1) : points.get(i);
            var p1 = points.get(i);
            var p2 = points.get(i + 1);
            var p3 = i + 2 < points.size() ? points.get(i + 2) : points.get(i + 1);

            double segmentLength = p2.t() - p1.t();
            if (segmentLength <= 0) return p1.value();
            double localT = (u - p1.t()) / segmentLength;
            return catmullRom(p0.value(), p1.value(), p2.value(), p3.value(), localT);
        }

        @Override public ClipShape onSplit(double originalDuration, double splitU, boolean leftHalf) {
            if (points.isEmpty()) return this;
            var renormalised = new ArrayList<Point>();
            if (leftHalf) {
                if (splitU <= 0) return new Spline(List.of(new Point(0, 0), new Point(1, 0)));
                for (var pt : points) {
                    if (pt.t() <= splitU) renormalised.add(new Point(pt.t() / splitU, pt.value()));
                }
                if (renormalised.isEmpty() || renormalised.getLast().t() < 1.0) {
                    renormalised.add(new Point(1.0, 0));
                }
            } else {
                double span = 1 - splitU;
                if (span <= 0) return new Spline(List.of(new Point(0, 0), new Point(1, 0)));
                for (var pt : points) {
                    if (pt.t() >= splitU) renormalised.add(new Point((pt.t() - splitU) / span, pt.value()));
                }
                if (renormalised.isEmpty() || renormalised.getFirst().t() > 0.0) {
                    renormalised.addFirst(new Point(0.0, 0));
                }
            }
            return new Spline(renormalised);
        }

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
    }
}
