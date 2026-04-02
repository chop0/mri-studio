package ax.xz.mri.model.field;

/**
 * Bilinear interpolation into the spatial field map.
 * Exact port of {@code getFieldAt()} and {@code bilerp()} from physics.ts.
 */
public final class FieldInterpolator {
    private FieldInterpolator() {}

    /**
     * Evaluate all field quantities at position ({@code rMm}, {@code zMm}).
     *
     * @param rMm  radial position in millimetres (always non-negative)
     * @param zMm  axial position in millimetres
     */
    public static FieldPoint interpolate(FieldMap f, double rMm, double zMm) {
        double rM  = Math.abs(rMm) * 1e-3;   // mm → m (for curvature terms)
        double zM  = zMm * 1e-3;
        double rm  = Math.abs(rMm);           // mm, used for grid lookup

        double dBz = bilerp(f.dBzUt, f.rMm, f.zMm, rm, zMm) * 1e-6;  // μT → T

        double mx0 = 0, my0 = 0, mz0 = 1;
        if (f.mx0 != null) {
            mx0 = bilerp(f.mx0, f.rMm, f.zMm, rm, zMm);
            my0 = bilerp(f.my0, f.rMm, f.zMm, rm, zMm);
            mz0 = bilerp(f.mz0, f.rMm, f.zMm, rm, zMm);
        }

        double B = f.b0n;
        return new FieldPoint(
            dBz, mx0, my0, mz0,
            rM + zM * zM / (2 * B),                           // gxm
            zM + (rM / 2) * (rM / 2) / (2 * B),              // gzm
            1 + 0.12 * sq(rM / (f.fovX / 2))
              + 0.08 * sq(zM / (f.fovZ / 2))                  // b1s
        );
    }

    /**
     * Bilinear interpolation; clamps to grid edges.
     * grid is indexed [r][z]; rArr and zArr are the axis sample positions.
     */
    public static double bilerp(double[][] grid, double[] rArr, double[] zArr, double r, double z) {
        int nr = rArr.length, nz = zArr.length;
        if (nr == 0 || nz == 0) return 0;
        // Handle single-point grids (no interpolation needed)
        if (nr == 1 && nz == 1) return grid[0][0];
        double ri = nr > 1 ? (r - rArr[0]) / (rArr[nr - 1] - rArr[0]) * (nr - 1) : 0;
        double zi = nz > 1 ? (z - zArr[0]) / (zArr[nz - 1] - zArr[0]) * (nz - 1) : 0;
        ri = Math.max(0, Math.min(nr - 1.001, ri));
        zi = Math.max(0, Math.min(nz - 1.001, zi));
        int r0 = (int) ri, z0 = (int) zi;
        double fr = ri - r0, fz = zi - z0;
        int r1 = Math.min(r0 + 1, nr - 1), z1 = Math.min(z0 + 1, nz - 1);
        return (1 - fr) * (1 - fz) * grid[r0][z0]
             +      fr  * (1 - fz) * grid[r1][z0]
             + (1 - fr) *      fz  * grid[r0][z1]
             +      fr  *      fz  * grid[r1][z1];
    }

    private static double sq(double x) { return x * x; }
}
