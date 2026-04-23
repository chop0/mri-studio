package ax.xz.mri.model.field;

/**
 * Bilinear interpolation into the spatial field map.
 *
 * <p>For each compiled coil, returns the per-point eigenfield components. No
 * shape formulas are hardcoded — every spatial dependence comes from the
 * user-authored eigenfield scripts baked into the compiled circuit.
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

        int nCoils = f.circuit == null ? 0 : f.circuit.coils().size();
        double[] coilEx = new double[nCoils];
        double[] coilEy = new double[nCoils];
        double[] coilEz = new double[nCoils];
        for (int i = 0; i < nCoils; i++) {
            var coil = f.circuit.coils().get(i);
            coilEx[i] = bilerp(coil.ex(), f.rMm, f.zMm, rm, zMm);
            coilEy[i] = bilerp(coil.ey(), f.rMm, f.zMm, rm, zMm);
            coilEz[i] = bilerp(coil.ez(), f.rMm, f.zMm, rm, zMm);
        }

        return new FieldPoint(staticBz, mx0, my0, mz0, coilEx, coilEy, coilEz);
    }

    /** Bilinear interpolation with edge clamping. */
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
