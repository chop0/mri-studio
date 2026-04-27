package ax.xz.mri.model.simulation;

/**
 * Declared spatial symmetry of an eigenfield script.
 *
 * <p>Used by {@link SimulationOutputFactory} to select the minimal grid that still
 * captures the field shape correctly. A script's declared symmetry is taken
 * on faith — if the DSL body violates it, the simulator will silently project
 * the field onto the wrong basis. Users who are unsure should pick
 * {@link #CARTESIAN_3D}, which has no invariance assumption.
 *
 * <h3>AXISYMMETRIC_Z</h3>
 * The eigenfield's value at {@code (r, φ, z)} is the value at {@code (r, 0, z)}
 * rotated by the azimuthal angle {@code φ} around the z-axis. Examples:
 * <ul>
 *   <li>A z-directed B0 from a Helmholtz pair: returns {@code (0, 0, f(r,z))}.</li>
 *   <li>A z-gradient: returns {@code (0, 0, z)}.</li>
 *   <li>A cylindrically-symmetric transverse pattern that rotates with the
 *       azimuth — e.g. the {@code B₁^+} of a quadrature birdcage.</li>
 * </ul>
 * The simulator stores one 2D (r, z) slice and reconstructs the full 3D field
 * on demand via a rotation.
 *
 * <h3>CARTESIAN_3D</h3>
 * No symmetry assumed. The eigenfield is sampled on a full (x, y, z) grid.
 * Use for surface coils, x-gradients, off-axis receive arrays, any field
 * whose azimuthal dependence is not a rigid rotation.
 */
public enum FieldSymmetry {
    AXISYMMETRIC_Z,
    CARTESIAN_3D
}
