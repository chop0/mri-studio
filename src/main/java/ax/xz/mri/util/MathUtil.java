package ax.xz.mri.util;

public final class MathUtil {
    private MathUtil() {}

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Clamp into [0, 1]. */
    public static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** Clamp into [-1, 1]. */
    public static double clampUnit(double v) {
        return Math.max(-1, Math.min(1, v));
    }

    public static double lerp(double a, double b, double t) {
        return a + t * (b - a);
    }
}
