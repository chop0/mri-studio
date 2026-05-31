package ax.xz.mri.model.circuit.starter;

import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SubstanceDocument;
import ax.xz.mri.state.ProjectState;

import java.util.List;

/** A named starting-point template for new circuits, shown in the new-circuit wizard. */
public interface CircuitStarter {
    String id();
    String name();
    String description();

    /**
     * Result of building a starter circuit: the circuit document itself plus
     * any newly-minted satellite documents the starter needed —
     * eigenfields (for coil shapes) and substances (for diamond / proton
     * ensembles placed in the FOV via {@link
     * ax.xz.mri.model.circuit.CircuitComponent.Substance Substance} blocks).
     * Existing docs matched by name+content in {@code state} are reused and
     * don't appear here. The caller dispatches structural mutations for each.
     */
    record Result(
        CircuitDocument circuit,
        List<EigenfieldDocument> newEigenfields,
        List<SubstanceDocument> newSubstances
    ) {
        public Result {
            newEigenfields = List.copyOf(newEigenfields == null ? List.of() : newEigenfields);
            newSubstances  = List.copyOf(newSubstances  == null ? List.of() : newSubstances);
        }
        public static Result of(CircuitDocument circuit) {
            return new Result(circuit, List.of(), List.of());
        }
    }

    Result build(ProjectNodeId id, String name, ProjectState state);
}
