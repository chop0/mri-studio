package ax.xz.mri.project;

import ax.xz.mri.model.substance.Substance;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Project-owned editable substance document.
 *
 * <p>Wraps a {@link Substance} (continuous-magnetisation or NV-ensemble)
 * alongside the standard {@link ProjectNode} envelope (id + name + kind).
 * The substance itself is the source of truth — wrapper provides only
 * persistence identity, undo granularity, and naming.
 */
public record SubstanceDocument(
    ProjectNodeId id,
    String name,
    Substance substance
) implements ProjectNode {

    public SubstanceDocument {
        if (id == null) throw new IllegalArgumentException("SubstanceDocument.id must be non-null");
        if (substance == null) throw new IllegalArgumentException("SubstanceDocument.substance must be non-null");
        if (name == null) name = "";
    }

    @Override
    @JsonIgnore
    public ProjectNodeKind kind() {
        return ProjectNodeKind.SUBSTANCE;
    }

    public SubstanceDocument withName(String newName) {
        return new SubstanceDocument(id, newName, substance);
    }

    public SubstanceDocument withSubstance(Substance newSubstance) {
        return new SubstanceDocument(id, name, newSubstance);
    }
}
