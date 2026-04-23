package ax.xz.mri.model.field;

/**
 * Interpolated field quantities at a single spatial point (r, z).
 *
 * <p>{@link #staticBz} is the total always-on longitudinal field at this point
 * after subtracting the simulation-frame reference — i.e. the local
 * off-resonance. Any Bloch–Siegert / average-Hamiltonian corrections from
 * fast-oscillating fields are folded into this scalar at build time.
 *
 * <p>{@link #ex}/{@link #ey}/{@link #ez} are per-driven-field eigenfield
 * components at this point — indexed by field number in the same order as
 * {@link FieldMap#dynamicFields}.
 *
 * <p>{@link #rxEx}/{@link #rxEy}/{@link #rxEz} are per-receive-coil sensitivity
 * components at this point — indexed by coil number in the same order as
 * {@link FieldMap#receiveCoils}.
 */
public record FieldPoint(
    double staticBz,
    double mx0,
    double my0,
    double mz0,
    double[] ex,
    double[] ey,
    double[] ez,
    double[] rxEx,
    double[] rxEy,
    double[] rxEz
) {}
