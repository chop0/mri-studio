package ax.xz.mri.model.sequence;

/** One RF/gradient step: [b1x, b1y, gx, gz, rfGate]. Deserialized from a JSON 5-element array. */
public record PulseStep(double b1x, double b1y, double gx, double gz, double rfGate) {
    public double  b1Magnitude() { return Math.sqrt(b1x * b1x + b1y * b1y); }
    public double  effectiveB1Magnitude() { return isRfOn() ? b1Magnitude() : 0.0; }
    public boolean isRfOn()      { return rfGate >= 0.5; }
}
