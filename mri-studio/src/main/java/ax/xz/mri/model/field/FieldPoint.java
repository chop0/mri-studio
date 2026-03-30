package ax.xz.mri.model.field;

/** Interpolated field quantities at a single spatial point (r, z). */
public record FieldPoint(
    double dBz,  // off-resonance field offset (T)
    double mx0,  // initial Mx
    double my0,  // initial My
    double mz0,  // initial Mz
    double gxm,  // effective Gx position factor (m, with field-curvature correction)
    double gzm,  // effective Gz position factor (m, with field-curvature correction)
    double b1s   // B1 inhomogeneity scale factor
) {}
