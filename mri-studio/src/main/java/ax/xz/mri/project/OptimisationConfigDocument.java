package ax.xz.mri.project;

/** Placeholder project node for future editable optimisation configurations. */
public record OptimisationConfigDocument(
    ProjectNodeId id,
    String name,
    ProjectNodeId sequenceId
) implements ProjectNode {
    @Override
    public ProjectNodeKind kind() {
        return ProjectNodeKind.OPTIMISATION_CONFIG;
    }
}
