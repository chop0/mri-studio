package ax.xz.mri.ui.workbench;

import ax.xz.mri.project.ProjectNode;

/** Human-facing labels for the semantic project tree. */
public final class ProjectDisplayNames {
    private ProjectDisplayNames() {}

    public static String label(ProjectNode node) {
        return switch (node.kind()) {
            case SEQUENCE -> "Sequence: " + node.name();
            case SIMULATION_CONFIG -> "Simulation: " + node.name();
            case EIGENFIELD -> "Eigenfield: " + node.name();
            case CIRCUIT -> "Circuit: " + node.name();
        };
    }
}
