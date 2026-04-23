package ax.xz.mri.optimisation;

/** Objective weights and mode for one optimisation problem. */
public record ObjectiveSpec(
    ObjectiveMode mode,
    double lamOut,
    double lamPow,
    double rfPenalty,
    double rfSmoothPenalty,
    double gateSwitchPenalty,
    double gateBinaryPenalty,
    double handoffPenalty
) {
    public ObjectiveSpec {
        if (mode == null) throw new IllegalArgumentException("mode must not be null");
    }

    public ObjectiveSpec withRfSmoothMultiplier(double multiplier) {
        return new ObjectiveSpec(
            mode,
            lamOut,
            lamPow,
            rfPenalty,
            rfSmoothPenalty * multiplier,
            gateSwitchPenalty,
            gateBinaryPenalty,
            handoffPenalty
        );
    }
}
