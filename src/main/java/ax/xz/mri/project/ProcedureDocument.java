package ax.xz.mri.project;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Project-level procedure document — the user-edited Java source of a
 * {@link ax.xz.mri.dsl.Script}, plus its display metadata.
 *
 * <p>The source is a full Java compilation unit (default package, no
 * class-level modifiers) implementing {@link ax.xz.mri.dsl.Script}. There
 * is no separate "passive vs iterative" axis any more — the script owns
 * its own lifecycle, so the document is just (id, name, source).
 */
public record ProcedureDocument(
    ProjectNodeId id,
    String name,
    String source
) implements ProjectNode {

    public ProcedureDocument {
        if (source == null || source.isBlank())
            throw new IllegalArgumentException("ProcedureDocument requires a non-blank source");
    }

    @Override
    @JsonIgnore
    public ProjectNodeKind kind() {
        return ProjectNodeKind.PROCEDURE;
    }

    public ProcedureDocument withName(String newName) {
        return new ProcedureDocument(id, newName, source);
    }

    public ProcedureDocument withSource(String newSource) {
        return new ProcedureDocument(id, name, newSource);
    }
}
