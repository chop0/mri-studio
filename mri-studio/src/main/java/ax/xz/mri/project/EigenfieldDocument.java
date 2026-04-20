package ax.xz.mri.project;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Project-level eigenfield definition.
 *
 * <p>An eigenfield is a named, DSL-defined 3D spatial field shape — the
 * vector field produced by a physical field source (magnet, gradient coil,
 * RF coil). The DSL source is the entire definition; there is no "preset" enum
 * and no fallback mode.
 *
 * <h3>Physical calibration</h3>
 * <p>Each eigenfield carries two pieces of metadata that make the field
 * calibration explicit:
 * <ul>
 *   <li>{@link #defaultMagnitude()} — the peak |Vec3| the script returns, in
 *       the eigenfield's declared units, when called with amplitude = 1.
 *       A unit-normalised script (returning {@code Vec3.of(0,0,1)} or
 *       {@code Vec3.of(0,0,z)}) uses {@code defaultMagnitude = 1}; a script
 *       that returns a peak of 5 (say, because it's not normalised) uses
 *       {@code defaultMagnitude = 5}.</li>
 *   <li>{@link #units()} — the physical units of that peak (e.g. {@code "T"},
 *       {@code "T/m"}, {@code "Hz"}). Empty means dimensionless.</li>
 * </ul>
 *
 * <p>An amplitude {@code A} applied to this eigenfield produces a peak
 * physical field of {@code A · defaultMagnitude} in {@code units}. Editors
 * use this mapping to show amplitudes with meaningful SI prefixes.
 *
 * <p>Eigenfields are shared across simulation configs — multiple configs can
 * reference the same eigenfield document by {@link ProjectNodeId}.
 */
public record EigenfieldDocument(
    ProjectNodeId id,
    String name,
    String description,
    String script,
    String units,
    double defaultMagnitude
) implements ProjectNode {

    public EigenfieldDocument {
        if (script == null || script.isBlank())
            throw new IllegalArgumentException("EigenfieldDocument requires a non-blank script");
        if (units == null)
            throw new IllegalArgumentException("EigenfieldDocument.units must be non-null (empty is allowed for dimensionless)");
        if (!(defaultMagnitude > 0) || !Double.isFinite(defaultMagnitude))
            throw new IllegalArgumentException("EigenfieldDocument.defaultMagnitude must be finite and positive, got " + defaultMagnitude);
    }

    @Override
    @JsonIgnore
    public ProjectNodeKind kind() {
        return ProjectNodeKind.EIGENFIELD;
    }

    /** Peak physical field produced by this eigenfield at amplitude = 1, in {@link #units()}. */
    public double physicalPeak(double amplitude) {
        return amplitude * defaultMagnitude;
    }

    public EigenfieldDocument withName(String newName) {
        return new EigenfieldDocument(id, newName, description, script, units, defaultMagnitude);
    }

    public EigenfieldDocument withDescription(String newDescription) {
        return new EigenfieldDocument(id, name, newDescription, script, units, defaultMagnitude);
    }

    public EigenfieldDocument withScript(String newScript) {
        return new EigenfieldDocument(id, name, description, newScript, units, defaultMagnitude);
    }

    public EigenfieldDocument withUnits(String newUnits) {
        return new EigenfieldDocument(id, name, description, script, newUnits, defaultMagnitude);
    }

    public EigenfieldDocument withDefaultMagnitude(double newMagnitude) {
        return new EigenfieldDocument(id, name, description, script, units, newMagnitude);
    }
}
