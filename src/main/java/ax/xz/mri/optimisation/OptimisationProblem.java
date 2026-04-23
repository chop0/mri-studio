package ax.xz.mri.optimisation;

/** Fully specified optimisation problem: geometry, sequence template, and objective. */
public record OptimisationProblem(
    ProblemGeometry geometry,
    SequenceTemplate sequenceTemplate,
    ObjectiveSpec objectiveSpec
) {
    public OptimisationProblem {
        if (geometry == null || sequenceTemplate == null || objectiveSpec == null) {
            throw new IllegalArgumentException("problem components must not be null");
        }
    }

    public OptimisationProblem withObjectiveSpec(ObjectiveSpec newObjectiveSpec) {
        return new OptimisationProblem(geometry, sequenceTemplate, newObjectiveSpec);
    }
}
