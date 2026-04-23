package ax.xz.mri.optimisation;

/** Hardware and relaxation defaults mirrored from the Python optimiser. */
public final class OptimisationHardwareLimits {
    public static final double GAMMA = 267.522e6;
    public static final double B1_MAX = 200e-6;
    public static final double GX_MAX = 30e-3;
    public static final double GZ_MAX = 30e-3;
    public static final double T1 = 300e-3;
    public static final double T2 = 300e-3;

    private OptimisationHardwareLimits() {
    }
}
