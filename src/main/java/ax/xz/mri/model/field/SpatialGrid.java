package ax.xz.mri.model.field;

import ax.xz.mri.model.simulation.Vec3;

/**
 * The spatial discretisation a simulation runs on.
 *
 * <p>Two impls today: a 2-D {@link CylindricalGrid} (an `(r, z)` slice at the
 * `φ = 0` half-plane, the historic fast path for axisymmetric setups), and a
 * full 3-D {@link CartesianGrid}. The choice is made once per simulation by
 * the compiled-simulation builder based on the {@link
 * ax.xz.mri.model.simulation.FieldSymmetry} declared by every eigenfield +
 * substance in the active circuit. Nothing downstream of the grid switches
 * on symmetry — it's a runtime hint, not a data shape.
 *
 * <p>All per-grid-point storage is a flat {@code double[]} of length
 * {@link #size()}. Cylindrical uses {@code index = iR · nZ + iZ}; Cartesian
 * uses {@code index = iX · (nY · nZ) + iY · nZ + iZ}. The grid is responsible
 * for mapping indices to physical positions and for interpolating flat
 * arrays at arbitrary 3-D positions.
 *
 * <p>Positions are metres throughout.
 */
public sealed interface SpatialGrid permits CylindricalGrid, CartesianGrid {

    /** Total number of grid points. */
    int size();

    /** Physical position of the grid point at the given flat index. */
    Vec3 position(int index);

    /**
     * Interpolate a per-grid-point scalar field at an arbitrary 3-D position.
     * Edges are clamped, not extrapolated.
     *
     * @param values flat array of length {@link #size()}.
     */
    double sampleScalar(double[] values, double xMetres, double yMetres, double zMetres);

    /**
     * Interpolate three coupled scalar fields (vector components) at an
     * arbitrary 3-D position. Cylindrical impl rotates the transverse
     * components by the azimuthal angle to give the physically correct
     * vector at off-axis points; the longitudinal (z) component is
     * rotation-invariant.
     */
    Vec3 sampleVec3(double[] ex, double[] ey, double[] ez,
                    double xMetres, double yMetres, double zMetres);
}
