package ax.xz.mri.project;

/** Bookmark child under a run that seeks the run to a specific capture. */
public record RunBookmarkDocument(
    ProjectNodeId id,
    String name,
    ProjectNodeId runId,
    ProjectNodeId targetCaptureId,
    BookmarkKind bookmarkKind,
    String iterationKey
) implements ProjectNode {
    @Override
    public ProjectNodeKind kind() {
        return ProjectNodeKind.RUN_BOOKMARK;
    }
}
