package ax.xz.mri.model.circuit.starter;

import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.state.ProjectState;

import java.util.List;

/** A named starting-point template for new circuits, shown in the new-circuit wizard. */
public interface CircuitStarter {
    String id();
    String name();
    String description();

    /**
     * Result of building a starter circuit: the circuit document itself plus
     * any newly-minted eigenfields the starter needed (existing eigenfields
     * matched by name+script in {@code state} are reused and don't appear in
     * {@link #newEigenfields()}). The caller dispatches structural mutations
     * for each.
     */
    record Result(CircuitDocument circuit, List<EigenfieldDocument> newEigenfields) {
        public Result {
            newEigenfields = List.copyOf(newEigenfields == null ? List.of() : newEigenfields);
        }
        public static Result of(CircuitDocument circuit) { return new Result(circuit, List.of()); }
    }

    Result build(ProjectNodeId id, String name, ProjectState state);
}
