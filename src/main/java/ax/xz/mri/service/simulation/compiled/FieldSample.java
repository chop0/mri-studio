package ax.xz.mri.service.simulation.compiled;

/**
 * Sample of the simulation's static field at one arbitrary 3-D point.
 *
 * <p>{@code staticBz} is the rotating-frame-referenced longitudinal field
 * (Tesla). {@code mx0}, {@code my0}, {@code mz0} are the initial magnetisation
 * the primary {@link ax.xz.mri.model.substance.ContinuousMagnetisation}
 * substance starts at (typically {@code (0, 0, mz0)} thermal equilibrium).
 * {@code coilEx / coilEy / coilEz} are the eigenfield components of every
 * compiled coil at this point — indexed in the order they appear in
 * {@link ax.xz.mri.service.circuit.CompiledCircuit#coils()} — already
 * multiplied by each coil's Tesla-per-amp sensitivity.
 *
 * <p>Returned by {@link CompiledSimulation#sampleAt}. Procedures that need
 * just one or a handful of points avoid the full {@link CompiledSimulation
 * #singleSpinTrajectory} sweep by reading this directly.
 */
public record FieldSample(
    double staticBz,
    double mx0, double my0, double mz0,
    double[] coilEx, double[] coilEy, double[] coilEz
) {
    public FieldSample {
        coilEx = coilEx == null ? new double[0] : coilEx;
        coilEy = coilEy == null ? new double[0] : coilEy;
        coilEz = coilEz == null ? new double[0] : coilEz;
    }
}
