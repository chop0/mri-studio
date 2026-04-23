package ax.xz.mri.model.field;

/**
 * Interpolated field quantities at one spatial point (r, z).
 *
 * <p>{@link #staticBz} is the rotating-frame-referenced longitudinal field at
 * this point — off-resonance including any Bloch–Siegert rollup from fast
 * sources.
 *
 * <p>{@link #coilEx}, {@link #coilEy}, {@link #coilEz} are the eigenfield
 * components of every compiled coil at this point, indexed in the same order
 * as {@link ax.xz.mri.service.circuit.CompiledCircuit#coils()}.
 */
public record FieldPoint(
    double staticBz,
    double mx0,
    double my0,
    double mz0,
    double[] coilEx,
    double[] coilEy,
    double[] coilEz
) {}
