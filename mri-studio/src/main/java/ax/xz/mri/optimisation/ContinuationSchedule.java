package ax.xz.mri.optimisation;

import java.util.List;

/** Ordered continuation stages for one optimisation run. */
public record ContinuationSchedule(List<ContinuationStage> stages) {
    public ContinuationSchedule {
        stages = List.copyOf(stages);
        if (stages.isEmpty()) throw new IllegalArgumentException("stages must not be empty");
    }

    public static ContinuationSchedule defaultForIterations(int totalIterations) {
        int total = Math.max(1, totalIterations);
        int first = Math.max(1, (int) Math.round(total * 0.40));
        int second = Math.max(1, (int) Math.round(total * 0.30));
        int third = Math.max(1, total - first - second);
        return new ContinuationSchedule(List.of(
            new ContinuationStage(first, 10.0),
            new ContinuationStage(second, 3.0),
            new ContinuationStage(third, 1.0)
        ));
    }
}
