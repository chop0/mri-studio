package ax.xz.mri.model.field;

import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.util.MathUtil;

/**
 * Full 3-D Cartesian grid. Grid points are physically located at
 * `(x[iX], y[iY], z[iZ])`. Flat-array layout is
 * {@code index = iX · (nY · nZ) + iY · nZ + iZ}.
 *
 * <p>Used when any eigenfield or substance in the active simulation is
 * non-axisymmetric — i.e. {@link
 * ax.xz.mri.model.simulation.FieldSymmetry#CARTESIAN_3D}.
 */
public record CartesianGrid(double[] xMetres, double[] yMetres, double[] zMetres) implements SpatialGrid {

    public CartesianGrid {
        if (xMetres == null || yMetres == null || zMetres == null) {
            throw new IllegalArgumentException("CartesianGrid: all axes must be non-null");
        }
        if (xMetres.length < 1 || yMetres.length < 1 || zMetres.length < 1) {
            throw new IllegalArgumentException("CartesianGrid: all axes must have at least one sample");
        }
        xMetres = xMetres.clone();
        yMetres = yMetres.clone();
        zMetres = zMetres.clone();
    }

    public int nX() { return xMetres.length; }
    public int nY() { return yMetres.length; }
    public int nZ() { return zMetres.length; }

    @Override public int size() { return xMetres.length * yMetres.length * zMetres.length; }

    @Override
    public Vec3 position(int index) {
        int nY = yMetres.length, nZ = zMetres.length;
        int iX = index / (nY * nZ);
        int rem = index - iX * nY * nZ;
        int iY = rem / nZ;
        int iZ = rem - iY * nZ;
        return new Vec3(xMetres[iX], yMetres[iY], zMetres[iZ]);
    }

    @Override
    public double sampleScalar(double[] values, double x, double y, double z) {
        return trilerp(values, x, y, z);
    }

    @Override
    public Vec3 sampleVec3(double[] ex, double[] ey, double[] ez, double x, double y, double z) {
        return new Vec3(trilerp(ex, x, y, z), trilerp(ey, x, y, z), trilerp(ez, x, y, z));
    }

    private double trilerp(double[] v, double x, double y, double z) {
        int nX = xMetres.length, nY = yMetres.length, nZ = zMetres.length;
        if (nX == 1 && nY == 1 && nZ == 1) return v[0];
        double xi = nX > 1 ? (x - xMetres[0]) / (xMetres[nX - 1] - xMetres[0]) * (nX - 1) : 0.0;
        double yi = nY > 1 ? (y - yMetres[0]) / (yMetres[nY - 1] - yMetres[0]) * (nY - 1) : 0.0;
        double zi = nZ > 1 ? (z - zMetres[0]) / (zMetres[nZ - 1] - zMetres[0]) * (nZ - 1) : 0.0;
        xi = MathUtil.clamp(xi, 0, nX - 1.0);
        yi = MathUtil.clamp(yi, 0, nY - 1.0);
        zi = MathUtil.clamp(zi, 0, nZ - 1.0);
        int x0 = (int) Math.min(xi, nX - 1.0001);
        int y0 = (int) Math.min(yi, nY - 1.0001);
        int z0 = (int) Math.min(zi, nZ - 1.0001);
        int x1 = Math.min(x0 + 1, nX - 1);
        int y1 = Math.min(y0 + 1, nY - 1);
        int z1 = Math.min(z0 + 1, nZ - 1);
        double fx = xi - x0, fy = yi - y0, fz = zi - z0;
        int yzStride = nY * nZ;
        double c000 = v[x0 * yzStride + y0 * nZ + z0];
        double c001 = v[x0 * yzStride + y0 * nZ + z1];
        double c010 = v[x0 * yzStride + y1 * nZ + z0];
        double c011 = v[x0 * yzStride + y1 * nZ + z1];
        double c100 = v[x1 * yzStride + y0 * nZ + z0];
        double c101 = v[x1 * yzStride + y0 * nZ + z1];
        double c110 = v[x1 * yzStride + y1 * nZ + z0];
        double c111 = v[x1 * yzStride + y1 * nZ + z1];
        double c00 = (1 - fx) * c000 + fx * c100;
        double c01 = (1 - fx) * c001 + fx * c101;
        double c10 = (1 - fx) * c010 + fx * c110;
        double c11 = (1 - fx) * c011 + fx * c111;
        double c0 = (1 - fy) * c00 + fy * c10;
        double c1 = (1 - fy) * c01 + fy * c11;
        return (1 - fz) * c0 + fz * c1;
    }

    @Override public double[] xMetres() { return xMetres.clone(); }
    @Override public double[] yMetres() { return yMetres.clone(); }
    @Override public double[] zMetres() { return zMetres.clone(); }
}
