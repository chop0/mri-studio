package ax.xz.mri.model.nv;

/** Built-in NV array layouts the geometry generator knows how to produce. */
public enum NvArrayShape {
    /** Linear array along x, uniformly random in {@code [-L/2, +L/2]}, at fixed depth. */
    LINEAR_X_RANDOM,
    /** Linear array along x, equally spaced in {@code [-L/2, +L/2)}, at fixed depth. */
    LINEAR_X_UNIFORM,
    /** 2-D grid in (x, y), equally spaced. {@code n} is interpreted as the per-axis count. */
    GRID_XY,
    /** Caller supplies {@link NvArrayGeometry#customCentres()} directly. */
    CUSTOM
}
