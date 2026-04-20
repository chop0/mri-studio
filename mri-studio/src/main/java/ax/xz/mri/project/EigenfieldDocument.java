package ax.xz.mri.project;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Project-level eigenfield definition.
 *
 * <p>An eigenfield is a named, DSL-defined 3D spatial field shape — the
 * normalised vector field produced by a physical field source (magnet,
 * gradient coil, RF coil) at unit amplitude. The DSL source is the entire
 * definition; there is no "preset" enum and no fallback mode.
 *
 * <p>Eigenfields are shared across simulation configs — multiple configs can
 * reference the same eigenfield document by {@link ProjectNodeId}.
 */
public record EigenfieldDocument(
    ProjectNodeId id,
    String name,
    String description,
    String script
) implements ProjectNode {

    public EigenfieldDocument {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("EigenfieldDocument requires a non-blank script");
        }
    }

    @Override
    @JsonIgnore
    public ProjectNodeKind kind() {
        return ProjectNodeKind.EIGENFIELD;
    }

    public EigenfieldDocument withName(String newName) {
        return new EigenfieldDocument(id, newName, description, script);
    }

    public EigenfieldDocument withDescription(String newDescription) {
        return new EigenfieldDocument(id, name, newDescription, script);
    }

    public EigenfieldDocument withScript(String newScript) {
        return new EigenfieldDocument(id, name, description, newScript);
    }
}
