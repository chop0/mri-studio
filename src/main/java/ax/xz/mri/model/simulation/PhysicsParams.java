package ax.xz.mri.model.simulation;

/**
 * User-editable physics knobs that feed into a {@link SimulationConfig} —
 * gyromagnetic ratio, T1/T2, FOV, integration step. Decoupled from the
 * config itself so wizards can collect values before knowing the circuit.
 */
public record PhysicsParams(
    double gamma,
    double t1Ms, double t2Ms,
    double sliceHalfMm, double fovZMm, double fovRMm,
    int nZ, int nR,
    double dtSeconds
) {
    public static final PhysicsParams DEFAULTS =
        new PhysicsParams(267.522e6, 1000, 100, 5, 20, 30, 50, 5, 1e-6);
}
