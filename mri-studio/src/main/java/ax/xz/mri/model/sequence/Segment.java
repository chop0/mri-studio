package ax.xz.mri.model.sequence;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One time segment: time-step size, number of free-precession steps, number of RF steps. */
public record Segment(
    double dt,
    @JsonProperty("n_free")  int nFree,
    @JsonProperty("n_pulse") int nPulse
) {
    public int    totalSteps()     { return nFree + nPulse; }
    public double durationMicros() { return totalSteps() * dt * 1e6; }
}
