package ax.xz.mri.model.field;

import java.util.List;

/**
 * Translates a legacy {@link ImportedFieldMap} (pre-rewrite, 4-hardcoded-channel
 * shape) into the general runtime {@link FieldMap}.
 *
 * <p>The legacy file format implied four channels: a static B0 (with a 2D
 * {@code dBz_uT} deviation map), an X-gradient, a Z-gradient, and an RF coil.
 * Each had a hardcoded spatial shape baked into the pre-rewrite
 * {@code FieldInterpolator}. This adapter reconstructs the same spatial
 * behaviour by populating three synthetic {@link DynamicFieldMap}s with the
 * same shapes as literal eigenfield samples.
 *
 * <p>Channel layout of the reconstructed FieldMap's {@code PulseStep.controls}
 * (matching the legacy 4-scalar layout, in order):
 * <ul>
 *   <li>0, 1 — RF I/Q</li>
 *   <li>2 — Gx amplitude</li>
 *   <li>3 — Gz amplitude</li>
 * </ul>
 * The RF gate is carried separately in {@code PulseStep.rfGate}.
 */
public final class ImportedFieldMapAdapter {
    private ImportedFieldMapAdapter() {}

    public static FieldMap adapt(ImportedFieldMap src) {
        var out = new FieldMap();
        out.rMm = src.rMm;
        out.zMm = src.zMm;
        out.gamma = src.gamma;
        out.t1 = src.t1;
        out.t2 = src.t2;
        out.fovX = src.fovX;
        out.fovZ = src.fovZ;
        out.segments = src.segments;
        out.sliceHalf = src.sliceHalf;
        out.b0Ref = src.b0n;
        out.mx0 = src.mx0;
        out.my0 = src.my0;
        out.mz0 = src.mz0;

        int nR = src.rMm == null ? 0 : src.rMm.length;
        int nZ = src.zMm == null ? 0 : src.zMm.length;

        // staticBz in Tesla: dBz_uT × 1e-6.
        out.staticBz = new double[nR][nZ];
        if (src.dBzUt != null) {
            for (int ri = 0; ri < nR; ri++) {
                for (int zi = 0; zi < nZ; zi++) {
                    out.staticBz[ri][zi] = src.dBzUt[ri][zi] * 1e-6;
                }
            }
        }

        double b0 = src.b0n != 0 ? src.b0n : 1.0;
        double fovXHalf = Math.max(src.fovX / 2.0, 1e-9);
        double fovZHalf = Math.max(src.fovZ / 2.0, 1e-9);

        // Synthetic RF: transverse, x-polarised with small spatial roll-off.
        //   b1s(r,z) = 1 + 0.12·(r/Rx)² + 0.08·(z/Rz)²
        double[][] rfEx = new double[nR][nZ];
        double[][] rfEy = new double[nR][nZ];
        double[][] rfEz = new double[nR][nZ];

        // Synthetic Gx: longitudinal with curvature correction.
        //   Ez(r,z) = r + z²/(2·b0)
        double[][] gxEx = new double[nR][nZ];
        double[][] gxEy = new double[nR][nZ];
        double[][] gxEz = new double[nR][nZ];

        // Synthetic Gz: longitudinal with curvature correction.
        //   Ez(r,z) = z + (r/2)²/(2·b0)
        double[][] gzEx = new double[nR][nZ];
        double[][] gzEy = new double[nR][nZ];
        double[][] gzEz = new double[nR][nZ];

        for (int ri = 0; ri < nR; ri++) {
            double rM = Math.abs(src.rMm[ri]) * 1e-3;
            for (int zi = 0; zi < nZ; zi++) {
                double zM = src.zMm[zi] * 1e-3;
                rfEx[ri][zi] = 1.0 + 0.12 * sq(rM / fovXHalf) + 0.08 * sq(zM / fovZHalf);
                gxEz[ri][zi] = rM + zM * zM / (2.0 * b0);
                gzEz[ri][zi] = zM + sq(rM / 2.0) / (2.0 * b0);
            }
        }

        // Channel layout matches the legacy 5-slot PulseStep order {b1x, b1y, gx, gz, rfGate}:
        // RF consumes channels 0–1 (I = b1x, Q = b1y), Gx is channel 2, Gz is channel 3.
        // deltaOmega = 0: we assume the legacy sequences were already at the Larmor frame.
        var rf = new DynamicFieldMap("RF", 0, 2, 0.0, 0.0, rfEx, rfEy, rfEz);
        var gx = new DynamicFieldMap("Gx", 2, 1, 0.0, 0.0, gxEx, gxEy, gxEz);
        var gz = new DynamicFieldMap("Gz", 3, 1, 0.0, 0.0, gzEx, gzEy, gzEz);
        out.dynamicFields = List.of(rf, gx, gz);

        return out;
    }

    private static double sq(double x) {
        return x * x;
    }
}
