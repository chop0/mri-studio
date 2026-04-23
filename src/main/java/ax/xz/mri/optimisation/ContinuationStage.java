package ax.xz.mri.optimisation;

/** One continuation stage, matching the current smoothness-reduction behaviour. */
public record ContinuationStage(int iterations, double rfSmoothMultiplier) {
    public ContinuationStage {
        if (iterations <= 0) throw new IllegalArgumentException("iterations must be positive");
    }
}
