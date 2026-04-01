package ax.xz.mri.project;

/** Project-owned capture document. */
public record CaptureDocument(
    ProjectNodeId id,
    String name,
    String iterationKey,
    ProjectNodeId sequenceSnapshotId
) implements ProjectNode {
    @Override
    public ProjectNodeKind kind() {
        return ProjectNodeKind.CAPTURE;
    }
}
