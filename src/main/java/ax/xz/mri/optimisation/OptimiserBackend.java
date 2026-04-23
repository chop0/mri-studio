package ax.xz.mri.optimisation;

/** Pluggable optimiser backend. */
public interface OptimiserBackend {
    OptimisationResult optimise(OptimisationRequest request, ObjectiveEngine engine);
}
