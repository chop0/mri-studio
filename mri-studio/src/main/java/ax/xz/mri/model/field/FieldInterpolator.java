package ax.xz.mri.model.field;

/**
 * Bilinear interpolation into the spatial field map.
 *
 * <p>Produces a {@link FieldPoint} with static Bz and per-dynamic-field
 * eigenfield components at the requested (r, z). No shape formulas are
 * hardcoded — every spatial dependence comes from the user-authored
 * eigenfield scripts baked into {@link FieldMap#dynamicFields}.
 */
public final class FieldInterpolator {
    private FieldInterpolator() {}

    public static FieldPoint interpolate(FieldMap f, double rMm, double zMm) {
        double rm = Math.abs(rMm);

        double staticBz = f.staticBz != null ? bilerp(f.staticBz, f.rMm, f.zMm, rm, zMm) : 0.0;

        double mx0 = 0, my0 = 0, mz0 = 1;
        if (f.mx0 != null) {
            mx0 = bilerp(f.mx0, f.rMm, f.zMm, rm, zMm);
            my0 = bilerp(f.my0, f.rMm, f.zMm, rm, zMm);
            mz0 = bilerp(f.mz0, f.rMm, f.zMm, rm, zMm);
        }

        int n = f.dynamicFields == null ? 0 : f.dynamicFields.size();
        double[] ex = new double[n];
        double[] ey = new double[n];
        double[] ez = new double[n];
        for (int i = 0; i < n; i++) {
            var df = f.dynamicFields.get(i);
            ex[i] = bilerp(df.ex, f.rMm, f.zMm, rm, zMm);
            ey[i] = bilerp(df.ey, f.rMm, f.zMm, rm, zMm);
            ez[i] = bilerp(df.ez, f.rMm, f.zMm, rm, zMm);
        }

        return new FieldPoint(staticBz, mx0, my0, mz0, ex, ey, ez);
    }

    /**
     * Bilinear interpolation; clamps to grid edges.
     * {@code grid} is indexed {@code [r][z]}; {@code rArr} and {@code zArr} are the axis sample positions.
     */
    public static double bilerp(double[][] grid, double[] rArr, double[] zArr, double r, double z) {
        int nr = rArr.length, nz = zArr.length;
        if (nr == 0 || nz == 0) return 0;
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
}
