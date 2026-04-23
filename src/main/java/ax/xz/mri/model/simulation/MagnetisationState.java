package ax.xz.mri.model.simulation;

/** Magnetisation vector at one instant in time. */
public record MagnetisationState(double mx, double my, double mz) {

    /** Thermal equilibrium: Mz = 1. */
    public static final MagnetisationState THERMAL_EQUILIBRIUM = new MagnetisationState(0, 0, 1);

    public double mPerp()    { return Math.sqrt(mx * mx + my * my); }
    public double phaseDeg() { return Math.atan2(my, mx) * 180.0 / Math.PI; }
    public double polarDeg() { return Math.atan2(mPerp(), mz) * 180.0 / Math.PI; }
}
