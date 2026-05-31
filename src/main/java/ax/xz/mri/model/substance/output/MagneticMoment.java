package ax.xz.mri.model.substance.output;

import ax.xz.mri.model.simulation.Vec3;

/**
 * The instantaneous magnetic dipole moment of one spin, expressed in
 * the simulation's rotating-frame convention (units of Tesla·m³ / μ₀,
 * matching the historic Bloch pipeline).
 *
 * <p>Every spin that emits a {@code MagneticMoment} couples to every
 * coil in the FOV via reciprocity — there is no wiring on the schematic;
 * the coupling is implicit / ambient. The {@link
 * ax.xz.mri.service.simulation.compiled.CompiledSimulation} pre-bakes per-spin
 * per-coil sensitivity weights at compile time and integrates
 * {@code S_c = Σ_d E_c(x_d) · moment_d} on every step.
 */
public record MagneticMoment(Vec3 moment) implements SpinOutput {

    public static final MagneticMoment ZERO = new MagneticMoment(Vec3.ZERO);

    public double x() { return moment.x(); }
    public double y() { return moment.y(); }
    public double z() { return moment.z(); }
}
