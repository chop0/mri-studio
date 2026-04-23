package ax.xz.mri.project;

import ax.xz.mri.model.simulation.FieldSymmetry;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Project-level eigenfield definition.
 *
 * <p>A named, DSL-defined spatial field shape — the vector field produced by
 * a physical field source (magnet, gradient coil, RF coil, receive coil).
 * The DSL source is the entire definition; there is no "preset" enum.
 *
 * <h3>Physical calibration</h3>
 * <ul>
 *   <li>{@link #defaultMagnitude()} — peak |Vec3| the script returns at unit
 *       amplitude, in the declared {@link #units()}.</li>
 *   <li>{@link #units()} — physical units of the peak (e.g. {@code "T"},
 *       {@code "T/m"}, {@code "Hz"}). Empty for dimensionless.</li>
 * </ul>
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
    @JsonProperty("default_magnitude") double defaultMagnitude,
    FieldSymmetry symmetry
) implements ProjectNode {

    public EigenfieldDocument {
        if (script == null || script.isBlank())
            throw new IllegalArgumentException("EigenfieldDocument requires a non-blank script");
        if (units == null)
            throw new IllegalArgumentException("EigenfieldDocument.units must be non-null (empty is allowed for dimensionless)");
        if (!(defaultMagnitude > 0) || !Double.isFinite(defaultMagnitude))
            throw new IllegalArgumentException("EigenfieldDocument.defaultMagnitude must be finite and positive, got " + defaultMagnitude);
        if (symmetry == null) symmetry = FieldSymmetry.AXISYMMETRIC_Z;
    }

    /** Convenience constructor that defaults {@link FieldSymmetry} to {@link FieldSymmetry#AXISYMMETRIC_Z}. */
    public EigenfieldDocument(ProjectNodeId id, String name, String description, String script,
                              String units, double defaultMagnitude) {
        this(id, name, description, script, units, defaultMagnitude, FieldSymmetry.AXISYMMETRIC_Z);
    }

    @Override
    @JsonIgnore
    public ProjectNodeKind kind() {
        return ProjectNodeKind.EIGENFIELD;
    }

    public double physicalPeak(double amplitude) {
        return amplitude * defaultMagnitude;
    }

    public EigenfieldDocument withName(String newName) {
        return new EigenfieldDocument(id, newName, description, script, units, defaultMagnitude, symmetry);
    }

    public EigenfieldDocument withDescription(String newDescription) {
        return new EigenfieldDocument(id, name, newDescription, script, units, defaultMagnitude, symmetry);
    }

    public EigenfieldDocument withScript(String newScript) {
        return new EigenfieldDocument(id, name, description, newScript, units, defaultMagnitude, symmetry);
    }

    public EigenfieldDocument withUnits(String newUnits) {
        return new EigenfieldDocument(id, name, description, script, newUnits, defaultMagnitude, symmetry);
    }

    public EigenfieldDocument withDefaultMagnitude(double newMagnitude) {
        return new EigenfieldDocument(id, name, description, script, units, newMagnitude, symmetry);
    }

    public EigenfieldDocument withSymmetry(FieldSymmetry newSymmetry) {
        return new EigenfieldDocument(id, name, description, script, units, defaultMagnitude, newSymmetry);
    }
}
