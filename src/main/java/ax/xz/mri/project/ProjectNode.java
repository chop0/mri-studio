package ax.xz.mri.project;

/** Common surface for persisted project nodes. */
public interface ProjectNode {
    ProjectNodeId id();

    ProjectNodeKind kind();

    String name();

    default int schema() {
        return 1;
    }
}
