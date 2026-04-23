package ax.xz.mri.ui.canvas;

/**
 * Orthographic 3-D → 2-D projection for the Bloch sphere.
 * Exact port of {@code project()} from canvas.ts.
 */
public final class Projection {
    private Projection() {}

    /**
     * @param theta  azimuth (radians)
     * @param phi    elevation (radians)
     * @param scale  pixels per unit
     * @return       [screenX, screenY, depth] — depth > 0 means in front
     */
    public static double[] project(double mx, double my, double mz,
                                   double theta, double phi,
                                   double scale, double cx, double cy) {
        double ct = Math.cos(theta), st = Math.sin(theta);
        double cp = Math.cos(phi),   sp = Math.sin(phi);
        return new double[]{
            cx + (mx * ct - my * st) * scale,
            cy + (mx * st * sp + my * ct * sp - mz * cp) * scale,
            -(mx * st * cp + my * ct * cp + mz * sp)
        };
    }
}
