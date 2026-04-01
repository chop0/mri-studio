package ax.xz.mri.project;

/** Stable identifier for a project tree node. */
public record ProjectNodeId(String value) {
    public ProjectNodeId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("project node id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
