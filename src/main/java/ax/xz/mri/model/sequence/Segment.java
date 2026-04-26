package ax.xz.mri.model.sequence;

/** One time segment: time-step size, number of free-precession steps, number of RF steps. */
public record Segment(
    double dt,
    int nFree,
    int nPulse
) {
    public int    totalSteps()     { return nFree + nPulse; }
    public double durationMicros() { return totalSteps() * dt * 1e6; }
}
