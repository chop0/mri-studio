package ax.xz.mri.project;

import ax.xz.mri.model.simulation.FieldSymmetry;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Project-level eigenfield definition.
 *
 * <p>A named, DSL-defined spatial field shape — the vector field produced by
 * a physical field source (magnet, gradient coil, RF coil, receive coil).
 * The DSL source is the entire definition; there is no "preset" enum.
 *
 * <p>The script is dimensionless: it returns the field's spatial <em>shape</em>,
 * normalized so that the peak {@code |Vec3|} at the reference position is
 * approximately {@code 1}. Each {@link ax.xz.mri.model.circuit.CircuitComponent.Coil}
 * that references this eigenfield supplies its own
 * {@code sensitivityT_per_A} — the actual peak Tesla per amp that
 * <em>that</em> coil produces. The physics is then
 * {@code B(r) = I_coil · sensitivity · shape(r)}.
 *
 * <p>The declared {@link #units()} live on the eigenfield only as a label
 * for UI display (axis readouts, colour-bar legends, etc.) — the simulator
 * doesn't multiply by them. Empty string for dimensionless.
 *
 * <h3>Spatial symmetry</h3>
 * {@link #symmetry()} tells the simulator which grid to sample the script on.
 * Axisymmetric fields use a 2D {@code (r, z)} grid rotated around {@code z}.
 * Cartesian 3D fields use a full 3D grid. Choosing the right mode is a
 * significant speedup when the physics permits it.
 */
public record EigenfieldDocument(
    ProjectNodeId id,
    String name,
    String description,
    String script,
    String units,
    FieldSymmetry symmetry
) implements ProjectNode {

    public EigenfieldDocument {
        if (script == null || script.isBlank())
            throw new IllegalArgumentException("EigenfieldDocument requires a non-blank script");
        if (units == null)
            throw new IllegalArgumentException("EigenfieldDocument.units must be non-null (empty is allowed for dimensionless)");
        if (symmetry == null) symmetry = FieldSymmetry.AXISYMMETRIC_Z;
    }

    /** Convenience constructor that defaults {@link FieldSymmetry} to {@link FieldSymmetry#AXISYMMETRIC_Z}. */
    public EigenfieldDocument(ProjectNodeId id, String name, String description, String script,
                              String units) {
        this(id, name, description, script, units, FieldSymmetry.AXISYMMETRIC_Z);
    }

    @Override
    @JsonIgnore
    public ProjectNodeKind kind() {
        return ProjectNodeKind.EIGENFIELD;
    }

    public EigenfieldDocument withName(String newName) {
        return new EigenfieldDocument(id, newName, description, script, units, symmetry);
    }

    public EigenfieldDocument withDescription(String newDescription) {
        return new EigenfieldDocument(id, name, newDescription, script, units, symmetry);
    }

    public EigenfieldDocument withScript(String newScript) {
        return new EigenfieldDocument(id, name, description, newScript, units, symmetry);
    }

    public EigenfieldDocument withUnits(String newUnits) {
        return new EigenfieldDocument(id, name, description, script, newUnits, symmetry);
    }

    public EigenfieldDocument withSymmetry(FieldSymmetry newSymmetry) {
        return new EigenfieldDocument(id, name, description, script, units, newSymmetry);
    }
}
