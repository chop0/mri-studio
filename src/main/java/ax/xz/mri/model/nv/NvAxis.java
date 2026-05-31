package ax.xz.mri.model.nv;

import ax.xz.mri.model.simulation.Vec3;

/**
 * Unit vector along the NV symmetry axis (the quantisation axis for the
 * spin-1 triplet).
 *
 * <p>v1 supports a single global axis for the whole ensemble. Real bulk
 * diamond samples carry NVs along all four {@code <111>} crystal directions
 * simultaneously; modelling the multi-orientation case is a v2 extension.
 */
public record NvAxis(double nx, double ny, double nz) {

    /** Default: NV axis along +z, the convention used by every test in this codebase. */
    public static final NvAxis AXIS_PLUS_Z = new NvAxis(0.0, 0.0, 1.0);

    /** The four canonical {@code <111>} crystallographic NV orientations, normalised. */
    public static final NvAxis AXIS_111      = of(+1, +1, +1);
    public static final NvAxis AXIS_111_BAR  = of(-1, -1, +1);
    public static final NvAxis AXIS_1_BAR_11 = of(-1, +1, -1);
    public static final NvAxis AXIS_11_BAR_1 = of(+1, -1, -1);

    public NvAxis {
        double m = Math.sqrt(nx*nx + ny*ny + nz*nz);
        if (!(m > 1e-30) || !Double.isFinite(m)) {
            throw new IllegalArgumentException("NvAxis must have non-zero length, got (" + nx + "," + ny + "," + nz + ")");
        }
        nx /= m; ny /= m; nz /= m;
    }

    /** Build a normalised axis from raw components. */
    public static NvAxis of(double x, double y, double z) {
        return new NvAxis(x, y, z);
    }

    public Vec3 asVec3() { return new Vec3(nx, ny, nz); }

    /** Project a field vector onto the NV axis ("axial" component, used in the rotating-frame Hamiltonian). */
    public double axial(Vec3 b) {
        return nx*b.x() + ny*b.y() + nz*b.z();
    }

    /** Magnitude of the transverse component of the field perpendicular to the NV axis. */
    public double transverseMagnitude(Vec3 b) {
        double a = axial(b);
        double total = b.x()*b.x() + b.y()*b.y() + b.z()*b.z();
        double perpSq = total - a*a;
        return perpSq <= 0.0 ? 0.0 : Math.sqrt(perpSq);
    }
}
