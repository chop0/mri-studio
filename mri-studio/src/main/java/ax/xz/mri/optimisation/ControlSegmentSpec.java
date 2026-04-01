package ax.xz.mri.optimisation;

/** Optimiser-facing description of one pulse segment. */
public record ControlSegmentSpec(double dt, int nFree, int nPulse, int nCtrl) {
    public ControlSegmentSpec {
        if (dt <= 0) throw new IllegalArgumentException("dt must be positive");
        if (nFree < 0 || nPulse < 0) throw new IllegalArgumentException("step counts must be non-negative");
        if (nCtrl <= 0) throw new IllegalArgumentException("nCtrl must be positive");
    }

    public int totalSteps() {
        return nFree + nPulse;
    }

    public int flattenedLength() {
        return totalSteps() * nCtrl;
    }

    public double durationMicros() {
        return totalSteps() * dt * 1e6;
    }
}
