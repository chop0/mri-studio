package ax.xz.mri.model.hardware;

/** Physical constants and scanner hardware limits. */
public final class HardwareLimits {
    /** Proton gyromagnetic ratio, rad/(s·T). */
    public static final double GAMMA   = 267.522e6;
    public static final double B1_MAX  = 25e-6;   // T
    public static final double GX_MAX  = 0.04;    // T/m
    public static final double GZ_MAX  = 0.04;    // T/m
    public static final double T1      = 1.0;     // s (default)
    public static final double T2      = 0.1;     // s (default)
    /** Guard against division by zero in Rodrigues rotation. */
    public static final double EPSILON = 1e-60;

    private HardwareLimits() {}
}
