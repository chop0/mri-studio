package ax.xz.mri.model.sequence;

/**
 * Identity tag for each concrete {@link ClipShape} variant. This is the handle
 * used to iterate shapes (for a tool palette, a "change shape" menu, or JSON
 * type discrimination) and to construct defaults for a given target
 * media duration.
 *
 * <p>Every entry is bound to a single {@link ClipShape} subclass. Keeping the
 * kind in an enum (rather than relying on {@code Class<? extends ClipShape>})
 * gives a cheap, allocation-free iteration order and a stable ID for
 * serialisation.
 */
public enum ClipKind {
    CONSTANT("Constant",   "Flat amplitude over the clip duration"),
    SINC("Sinc",           "Sinc pulse with configurable bandwidth and Hanning window"),
    TRAPEZOID("Trapezoid", "Linear ramp up, flat top, linear ramp down"),
    GAUSSIAN("Gaussian",   "Gaussian envelope with configurable sigma"),
    TRIANGLE("Triangle",   "Linear ramp to peak, then ramp down"),
    SINE("Sine",           "Sinusoidal oscillation with configurable frequency and phase"),
    SPLINE("Spline",       "Cubic spline through draggable control points");

    private final String displayName;
    private final String description;

    ClipKind(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() { return displayName; }
    public String description() { return description; }

    /**
     * Build a fresh shape instance of this kind with default parameters
     * appropriate to the given media duration. Duration-independent shapes
     * (constant, sinc, triangle, sine, spline) ignore the parameter.
     */
    public ClipShape defaultFor(double mediaDurationMicros) {
        return switch (this) {
            case CONSTANT  -> new ClipShape.Constant();
            case SINC      -> ClipShape.Sinc.defaults();
            case TRAPEZOID -> ClipShape.Trapezoid.defaultFor(mediaDurationMicros);
            case GAUSSIAN  -> ClipShape.Gaussian.defaultFor(mediaDurationMicros);
            case TRIANGLE  -> ClipShape.Triangle.defaults();
            case SINE      -> ClipShape.Sine.defaults();
            case SPLINE    -> ClipShape.Spline.defaults();
        };
    }
}
