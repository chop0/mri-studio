package ax.xz.mri.model.circuit.starter;

import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;

/** A named starting-point template for new circuits, shown in the new-circuit wizard. */
public interface CircuitStarter {
    String id();
    String name();
    String description();
    CircuitDocument build(ProjectNodeId id, String name, ProjectRepository repository);
}
