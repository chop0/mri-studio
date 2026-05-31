package ax.xz.mri.model.field;

import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.util.MathUtil;

/**
 * 2-D axisymmetric grid on the `(r, z)` half-plane at `φ = 0`. Grid points
 * are physically located at `(r, 0, z)`; samples at off-axis positions
 * `(x, y, z)` use `r = √(x² + y²)` and azimuthally-rotate transverse field
 * components to give the physically correct 3-D vector field.
 *
 * <p>Used when every eigenfield + substance in the active simulation is
 * {@link ax.xz.mri.model.simulation.FieldSymmetry#AXISYMMETRIC_Z}.
 */
public record CylindricalGrid(double[] rMetres, double[] zMetres) implements SpatialGrid {

    public CylindricalGrid {
        if (rMetres == null || zMetres == null) {
            throw new IllegalArgumentException("CylindricalGrid: rMetres and zMetres must be non-null");
        }
        if (rMetres.length < 1 || zMetres.length < 1) {
            throw new IllegalArgumentException("CylindricalGrid: both axes must have at least one sample");
        }
        rMetres = rMetres.clone();
        zMetres = zMetres.clone();
    }

    public int nR() { return rMetres.length; }
    public int nZ() { return zMetres.length; }

    @Override public int size() { return rMetres.length * zMetres.length; }

    @Override
    public Vec3 position(int index) {
        int nZ = zMetres.length;
        int iR = index / nZ;
        int iZ = index % nZ;
        return new Vec3(rMetres[iR], 0.0, zMetres[iZ]);
    }

    @Override
    public double sampleScalar(double[] values, double x, double y, double z) {
        return bilerp(values, Math.hypot(x, y), z);
    }

    @Override
    public Vec3 sampleVec3(double[] ex, double[] ey, double[] ez, double x, double y, double z) {
        double r = Math.hypot(x, y);
        double exV = bilerp(ex, r, z);
        double eyV = bilerp(ey, r, z);
        double ezV = bilerp(ez, r, z);
        // Stored components are at (r, 0, z). At (r·cos φ, r·sin φ, z) the
        // transverse vector rotates by φ around z; ez stays invariant.
        if (r > 0) {
            double invR = 1.0 / r;
            double cos = x * invR;
            double sin = y * invR;
            return new Vec3(exV * cos - eyV * sin, exV * sin + eyV * cos, ezV);
        }
        return new Vec3(exV, eyV, ezV);
    }

    /** Bilinear lookup on the flat (iR · nZ + iZ) layout, with edge clamp. */
    private double bilerp(double[] values, double r, double z) {
        int nR = rMetres.length, nZ = zMetres.length;
        if (nR == 1 && nZ == 1) return values[0];
        double ri = nR > 1 ? (r - rMetres[0]) / (rMetres[nR - 1] - rMetres[0]) * (nR - 1) : 0.0;
        double zi = nZ > 1 ? (z - zMetres[0]) / (zMetres[nZ - 1] - zMetres[0]) * (nZ - 1) : 0.0;
        ri = MathUtil.clamp(ri, 0, nR - 1.0);
        zi = MathUtil.clamp(zi, 0, nZ - 1.0);
        int r0 = (int) Math.min(ri, nR - 1.0001);
        int z0 = (int) Math.min(zi, nZ - 1.0001);
        int r1 = Math.min(r0 + 1, nR - 1);
        int z1 = Math.min(z0 + 1, nZ - 1);
        double fr = ri - r0, fz = zi - z0;
        return (1 - fr) * (1 - fz) * values[r0 * nZ + z0]
             +      fr  * (1 - fz) * values[r1 * nZ + z0]
             + (1 - fr) *      fz  * values[r0 * nZ + z1]
             +      fr  *      fz  * values[r1 * nZ + z1];
    }

    @Override public double[] rMetres() { return rMetres.clone(); }
    @Override public double[] zMetres() { return zMetres.clone(); }
}
