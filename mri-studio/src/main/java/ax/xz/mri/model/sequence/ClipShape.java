package ax.xz.mri.model.sequence;

/** Available waveform shapes for signal clips. */
public enum ClipShape {
    CONSTANT("Constant", "Flat amplitude over the clip duration"),
    SINC("Sinc", "Sinc pulse with configurable bandwidth and Hanning window"),
    TRAPEZOID("Trapezoid", "Linear ramp up, flat top, linear ramp down"),
    GAUSSIAN("Gaussian", "Gaussian envelope with configurable sigma"),
    SPLINE("Spline", "Cubic spline through draggable control points"),
    TRIANGLE("Triangle", "Linear ramp to peak, then ramp down"),
    SINE("Sine", "Sinusoidal oscillation with configurable frequency and phase");

    private final String displayName;
    private final String description;

    ClipShape(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() { return displayName; }
    public String description() { return description; }
}
