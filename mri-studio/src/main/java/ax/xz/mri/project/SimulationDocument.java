package ax.xz.mri.project;

/** Placeholder project node for future editable simulation definitions. */
public record SimulationDocument(
    ProjectNodeId id,
    String name,
    ProjectNodeId sequenceId,
    ProjectNodeId captureId
) implements ProjectNode {
    @Override
    public ProjectNodeKind kind() {
        return ProjectNodeKind.SIMULATION;
    }
}
