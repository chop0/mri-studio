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
 *
 * <p>{@code units} records what the amplitude scalar is multiplied by to
 * produce a physical quantity. For a gradient coil the eigenfield returns a
 * spatially-varying B (per unit of amplitude) and units are typically
 * {@code "T/m"}; for an RF loop the eigenfield is a unit-normalised B⊥ and
 * units are {@code "T"}. Display code uses this to label axes and pick
 * SI-prefix scales (μT, kHz, etc.).
 */
public record EigenfieldDocument(
    ProjectNodeId id,
    String name,
    String description,
    String script,
    String units
) implements ProjectNode {

    public EigenfieldDocument {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("EigenfieldDocument requires a non-blank script");
        }
        if (units == null) units = "";
    }

    @Override
    @JsonIgnore
    public ProjectNodeKind kind() {
        return ProjectNodeKind.EIGENFIELD;
    }

    public EigenfieldDocument withName(String newName) {
        return new EigenfieldDocument(id, newName, description, script, units);
    }

    public EigenfieldDocument withDescription(String newDescription) {
        return new EigenfieldDocument(id, name, newDescription, script, units);
    }

    public EigenfieldDocument withScript(String newScript) {
        return new EigenfieldDocument(id, name, description, newScript, units);
    }

    public EigenfieldDocument withUnits(String newUnits) {
        return new EigenfieldDocument(id, name, description, script, newUnits);
    }
}
