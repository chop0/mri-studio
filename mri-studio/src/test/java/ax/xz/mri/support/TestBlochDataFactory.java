package ax.xz.mri.support;

import ax.xz.mri.model.field.DynamicFieldMap;
import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.scenario.Scenario;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;

import java.util.List;
import java.util.Map;

/**
 * Small deterministic Bloch documents used by unit tests.
 *
 * <p>Uses the post-rewrite 4-channel layout: controls = [b1x, b1y, gx, gz],
 * rfGate separate. Each synthetic FieldMap carries three dynamic fields (RF
 * QUADRATURE, Gx REAL, Gz REAL) with hand-tuned spatial maps.
 */
public final class TestBlochDataFactory {
    private TestBlochDataFactory() {
    }

    private static PulseStep step(double b1x, double b1y, double gx, double gz, double rfGate) {
        return new PulseStep(new double[]{b1x, b1y, gx, gz}, rfGate);
    }

    public static BlochData sampleDocument() {
        return new BlochData(
            sampleField(),
            List.of(
                new BlochData.IsochromatDef("Centre", "#00aa55", true),
                new BlochData.IsochromatDef("Edge", "#2266dd", true)
            ),
            Map.of(
                "Full GRAPE", new Scenario(Map.of(
                    "0", pulseB(),
                    "3", pulseA()
                )),
                "Baseline", new Scenario(Map.of(
                    "10", pulseB(),
                    "2", pulseA(),
                    "1", pulseA()
                ))
            )
        );
    }

    public static BlochData brokenDocumentMissingSegments() {
        var field = sampleField();
        field.segments = null;
        return new BlochData(
            field,
            List.of(new BlochData.IsochromatDef("Broken", "#aa0000", true)),
            Map.of("Broken", new Scenario(Map.of("0", pulseA())))
        );
    }

    public static List<PulseSegment> pulseA() {
        return List.of(
            new PulseSegment(List.of(
                step(1.0e-6, 0, 0, 0, 1.0),
                step(1.0e-6, 0, 0, 0, 1.0)
            )),
            new PulseSegment(List.of(
                step(0, 0, 0, 0.010, 0),
                step(0, 0, 0, -0.010, 0)
            ))
        );
    }

    public static List<PulseSegment> pulseB() {
        return List.of(
            new PulseSegment(List.of(
                step(2.0e-6, 0, 0, 0, 1.0),
                step(0.5e-6, 0.5e-6, 0, 0, 1.0)
            )),
            new PulseSegment(List.of(
                step(0, 0, 0.008, 0.018, 0),
                step(0, 0, -0.008, -0.018, 0)
            ))
        );
    }

    public static BlochData incoherentTransverseDocument() {
        return new BlochData(
            incoherentField(),
            List.of(),
            Map.of()
        );
    }

    public static List<PulseSegment> freePrecessionPulse() {
        return List.of(new PulseSegment(List.of(step(0, 0, 0, 0, 0))));
    }

    private static FieldMap sampleField() {
        var field = new FieldMap();
        field.rMm = new double[]{0, 15, 30};
        field.zMm = new double[]{-10, 0, 10};
        field.b0Ref = 1.5;
        // staticBz in Tesla: legacy dBz_uT × 1e-6.
        double[][] dBzUt = {
            {0.0, 4.0, 1.5},
            {1.0, -2.0, 0.5},
            {2.5, -1.0, 0.0}
        };
        field.staticBz = new double[3][3];
        for (int r = 0; r < 3; r++) for (int z = 0; z < 3; z++) field.staticBz[r][z] = dBzUt[r][z] * 1e-6;
        field.mx0 = null;
        field.my0 = null;
        field.mz0 = null;
        field.fovX = 0.04;
        field.fovZ = 0.04;
        field.gamma = 267.5e6;
        field.t1 = 1.0;
        field.t2 = 0.08;
        field.sliceHalf = 0.005;
        field.segments = List.of(
            new Segment(1.0e-6, 0, 2),
            new Segment(1.0e-6, 2, 0)
        );
        field.dynamicFields = legacyDynamicFields(field.rMm, field.zMm, field.b0Ref, field.fovX, field.fovZ);
        return field;
    }

    private static FieldMap incoherentField() {
        var field = new FieldMap();
        field.rMm = new double[]{0, 15, 30};
        field.zMm = new double[]{-10, 0, 10};
        field.b0Ref = 1.5;
        field.staticBz = new double[3][3];
        field.mx0 = new double[][]{
            {1.0, -0.5, -0.5},
            {-0.5, -0.5, 1.0},
            {-0.5, 1.0, -0.5}
        };
        field.my0 = new double[][]{
            {0.0, 0.8660254038, -0.8660254038},
            {0.8660254038, -0.8660254038, 0.0},
            {-0.8660254038, 0.0, 0.8660254038}
        };
        field.mz0 = new double[][]{
            {0.0, 0.0, 0.0},
            {0.0, 0.0, 0.0},
            {0.0, 0.0, 0.0}
        };
        field.fovX = 0.04;
        field.fovZ = 0.04;
        field.gamma = 267.5e6;
        field.t1 = 1.0e9;
        field.t2 = 1.0e9;
        field.sliceHalf = 0.005;
        field.segments = List.of(
            new Segment(1.0e-6, 1, 0)
        );
        field.dynamicFields = legacyDynamicFields(field.rMm, field.zMm, field.b0Ref, field.fovX, field.fovZ);
        return field;
    }

    /** Reconstruct the pre-rewrite hardcoded shapes as explicit eigenfields. */
    private static List<DynamicFieldMap> legacyDynamicFields(double[] rMm, double[] zMm, double b0, double fovX, double fovZ) {
        int nR = rMm.length;
        int nZ = zMm.length;
        double fovXHalf = Math.max(fovX / 2, 1e-9);
        double fovZHalf = Math.max(fovZ / 2, 1e-9);
        double[][] rfEx = new double[nR][nZ];
        double[][] rfEy = new double[nR][nZ];
        double[][] rfEz = new double[nR][nZ];
        double[][] gxEz = new double[nR][nZ];
        double[][] gzEz = new double[nR][nZ];
        for (int ri = 0; ri < nR; ri++) {
            double rM = Math.abs(rMm[ri]) * 1e-3;
            for (int zi = 0; zi < nZ; zi++) {
                double zM = zMm[zi] * 1e-3;
                rfEx[ri][zi] = 1.0 + 0.12 * sq(rM / fovXHalf) + 0.08 * sq(zM / fovZHalf);
                gxEz[ri][zi] = rM + zM * zM / (2 * b0);
                gzEz[ri][zi] = zM + sq(rM / 2) / (2 * b0);
            }
        }
        return List.of(
            new DynamicFieldMap("RF", 0, 2, 0.0, 0.0, rfEx, rfEy, rfEz),
            new DynamicFieldMap("Gx", 2, 1, 0.0, 0.0, new double[nR][nZ], new double[nR][nZ], gxEz),
            new DynamicFieldMap("Gz", 3, 1, 0.0, 0.0, new double[nR][nZ], new double[nR][nZ], gzEz)
        );
    }

    private static double sq(double v) { return v * v; }
}
