package ax.xz.mri.model.field;

/**
 * Interpolated field quantities at a single spatial point (r, z).
 *
 * <p>{@link #staticBz} is the total always-on longitudinal field at this point
 * after subtracting the simulation-frame reference — i.e. the local
 * off-resonance. Any Bloch–Siegert / average-Hamiltonian corrections from
 * fast-oscillating fields are folded into this scalar at build time.
 *
 * <p>{@link #ex}/{@link #ey}/{@link #ez} are per-field eigenfield components
 * at this point — indexed by field number in the same order as
 * {@link FieldMap#dynamicFields}. Each array has length equal to the number
 * of dynamic fields; no static fields appear here.
 */
public record FieldPoint(
    double staticBz,    // rotating-frame static Bz (after reference subtraction + corrections), T
    double mx0,         // initial Mx
    double my0,         // initial My
    double mz0,         // initial Mz
    double[] ex,        // per-dynamic-field E_x at this point (unitless eigenfield)
    double[] ey,        // per-dynamic-field E_y at this point
    double[] ez         // per-dynamic-field E_z at this point
) {}
