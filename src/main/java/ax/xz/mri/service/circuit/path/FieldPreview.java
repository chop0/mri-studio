package ax.xz.mri.service.circuit.path;

import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.model.simulation.dsl.EigenfieldScript;

import java.util.Objects;

/**
 * Cosmetic field preview for a single coil/clip combination. Samples the
 * coil's eigenfield over a 3D box and reports peak |B| (vector + position)
 * plus RMS magnitude across the box.
 *
 * <p>The eigenfield is dimensionless shape; the coil's
 * {@code sensitivityT_per_A} and the coil's preview current together
 * convert the shape into Tesla via {@code B(r) = I · sensitivity · shape(r)}.
 *
 * <p>This is a UI affordance — it deliberately doesn't try to match the
 * simulator's grid or the project's FOV. It samples a regular cube around
 * the origin so the result is comparable across coils and across runs.
 */
public final class FieldPreview {
    private FieldPreview() {}

    /** Default sampling box (±10 cm) — same default as {@code EigenfieldPreviewCanvas}. */
    public static final double DEFAULT_HALF_EXTENT_M = 0.10;

    /** Default samples per axis (cubed: 11³ = 1,331 points). */
    public static final int DEFAULT_SAMPLES_PER_AXIS = 11;

    /**
     * Result of sampling one coil's field over a 3D box.
     *
     * @param scaleTesla physical scale applied to the eigenfield shape:
     *                   {@code I_coil × coil.sensitivityT_per_A}
     * @param peakField peak {@code |B|} found on the grid (Tesla)
     * @param peakVector vector at the peak (Tesla)
     * @param peakX peak's x-coordinate (metres)
     * @param peakY peak's y-coordinate (metres)
     * @param peakZ peak's z-coordinate (metres)
     * @param rmsField root-mean-square of {@code |B|} over the grid (Tesla)
     * @param halfExtentM half-side of the cube the script was sampled on
     * @param samplesPerAxis grid resolution per axis
     */
    public record Result(
        double scaleTesla,
        double peakField,
        Vec3 peakVector,
        double peakX, double peakY, double peakZ,
        double rmsField,
        double halfExtentM,
        int samplesPerAxis
    ) {}

    /**
     * Sample the field on a centered cube. Uses
     * {@link #DEFAULT_HALF_EXTENT_M} and {@link #DEFAULT_SAMPLES_PER_AXIS}.
     */
    public static Result compute(EigenfieldScript script, double sensitivityT_per_A, double currentAmps) {
        return compute(script, sensitivityT_per_A, currentAmps,
            DEFAULT_HALF_EXTENT_M, DEFAULT_SAMPLES_PER_AXIS);
    }

    /**
     * Sample the field on a centered cube of given half-extent and resolution.
     */
    public static Result compute(EigenfieldScript script, double sensitivityT_per_A, double currentAmps,
                                  double halfExtentM, int samplesPerAxis) {
        Objects.requireNonNull(script, "script");
        if (!(halfExtentM > 0)) throw new IllegalArgumentException("halfExtentM must be > 0");
        if (samplesPerAxis < 2) throw new IllegalArgumentException("samplesPerAxis must be >= 2");

        double scale = currentAmps * sensitivityT_per_A;

        double peakMag = 0;
        Vec3 peakVec = Vec3.ZERO;
        double peakX = 0, peakY = 0, peakZ = 0;
        double sumSq = 0;
        long count = 0;

        for (int ix = 0; ix < samplesPerAxis; ix++) {
            double x = -halfExtentM + 2 * halfExtentM * ix / (samplesPerAxis - 1);
            for (int iy = 0; iy < samplesPerAxis; iy++) {
                double y = -halfExtentM + 2 * halfExtentM * iy / (samplesPerAxis - 1);
                for (int iz = 0; iz < samplesPerAxis; iz++) {
                    double z = -halfExtentM + 2 * halfExtentM * iz / (samplesPerAxis - 1);

                    Vec3 shape;
                    try {
                        shape = script.evaluate(x, y, z);
                    } catch (Throwable t) {
                        shape = Vec3.ZERO;
                    }
                    if (shape == null) shape = Vec3.ZERO;
                    shape = sanitise(shape);

                    double bx = shape.x() * scale;
                    double by = shape.y() * scale;
                    double bz = shape.z() * scale;
                    double magSq = bx * bx + by * by + bz * bz;
                    if (magSq > peakMag * peakMag) {
                        peakMag = Math.sqrt(magSq);
                        peakVec = new Vec3(bx, by, bz);
                        peakX = x; peakY = y; peakZ = z;
                    }
                    sumSq += magSq;
                    count++;
                }
            }
        }
        double rms = count == 0 ? 0 : Math.sqrt(sumSq / count);
        return new Result(scale, peakMag, peakVec, peakX, peakY, peakZ, rms,
            halfExtentM, samplesPerAxis);
    }

    /**
     * Build an {@link EigenfieldScript} that returns the physical Tesla field
     * for use with {@code EigenfieldPreviewCanvas}: pre-multiplies the
     * dimensionless shape by {@code I × sensitivity}.
     */
    public static EigenfieldScript scaledScript(EigenfieldScript script,
                                                 double sensitivityT_per_A,
                                                 double currentAmps) {
        Objects.requireNonNull(script, "script");
        double scale = currentAmps * sensitivityT_per_A;
        return (x, y, z) -> {
            Vec3 v;
            try {
                v = script.evaluate(x, y, z);
            } catch (Throwable t) {
                v = Vec3.ZERO;
            }
            if (v == null) return Vec3.ZERO;
            return new Vec3(v.x() * scale, v.y() * scale, v.z() * scale);
        };
    }

    private static Vec3 sanitise(Vec3 v) {
        double x = Double.isFinite(v.x()) ? v.x() : 0;
        double y = Double.isFinite(v.y()) ? v.y() : 0;
        double z = Double.isFinite(v.z()) ? v.z() : 0;
        return new Vec3(x, y, z);
    }

    /**
     * Format a Tesla value with auto-scaled SI prefix.
     */
    public static String formatTesla(double value) {
        double abs = Math.abs(value);
        if (abs == 0) return "0 T";
        if (abs >= 1.0) return String.format("%.3f T", value);
        if (abs >= 1e-3) return String.format("%.3f mT", value * 1e3);
        if (abs >= 1e-6) return String.format("%.3f µT", value * 1e6);
        if (abs >= 1e-9) return String.format("%.3f nT", value * 1e9);
        return String.format("%.3g T", value);
    }
}
