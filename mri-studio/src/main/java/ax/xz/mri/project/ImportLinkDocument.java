package ax.xz.mri.project;

/** Project-side link to a legacy combined JSON source. */
public record ImportLinkDocument(
    ProjectNodeId id,
    String name,
    String sourcePath,
    ReloadMode reloadMode
) implements ProjectNode {
    public ImportLinkDocument {
        if (reloadMode == null) reloadMode = ReloadMode.MANUAL;
    }

    @Override
    public ProjectNodeKind kind() {
        return ProjectNodeKind.IMPORT_LINK;
    }
}
