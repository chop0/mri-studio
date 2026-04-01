package ax.xz.mri.project;

/** Imported capture synthesized from one scenario iteration. */
public record ImportedCaptureDocument(
    ProjectNodeId id,
    ProjectNodeId importLinkId,
    ProjectNodeId scenarioId,
    ProjectNodeId runId,
    String name,
    String sourceScenarioName,
    String iterationKey,
    ProjectNodeId sequenceSnapshotId
) implements ProjectNode {
    @Override
    public ProjectNodeKind kind() {
        return ProjectNodeKind.IMPORTED_CAPTURE;
    }
}
