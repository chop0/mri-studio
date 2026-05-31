package ax.xz.mri.model.simulation;

/**
 * Wizard-stage simulation parameter bundle. Currently just the integration
 * step — spatial layout (extent + resolution) and tissue physics live on the
 * substance documents the circuit references.
 *
 * <p>This bundle stays as its own record so the wizard's
 * {@code PhysicsParamsStep} has a coherent value type to collect, and so
 * future global parameters (e.g. solver tolerance) can be added without
 * widening {@link SimulationConfig}'s signature.
 */
public record PhysicsParams(double dtSeconds) {
    public static final PhysicsParams DEFAULTS = new PhysicsParams(1e-6);

    public PhysicsParams {
        if (!(dtSeconds > 0) || !Double.isFinite(dtSeconds))
            throw new IllegalArgumentException("dtSeconds must be a finite positive value, got " + dtSeconds);
    }
}
