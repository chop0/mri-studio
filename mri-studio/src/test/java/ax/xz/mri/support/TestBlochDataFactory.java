package ax.xz.mri.support;

import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.service.circuit.CompiledCircuit;
import ax.xz.mri.service.circuit.CompiledCircuit.CompiledCoil;
import ax.xz.mri.service.circuit.CompiledCircuit.CompiledProbe;
import ax.xz.mri.service.circuit.CompiledCircuit.CompiledSource;
import ax.xz.mri.service.circuit.CompiledCircuit.TopologyLink;

import java.util.List;

/**
 * Small deterministic Bloch documents used by unit tests.
 *
 * <p>Channel layout: controls = [rf_I, rf_Q, gx, gz]. The synthetic
 * {@link CompiledCircuit} carries three drive sources (RF QUADRATURE, Gx REAL,
 * Gz REAL), three coils (each with a deterministic eigenfield shape), and one
 * probe wired to the RF coil for receive.
 */
public final class TestBlochDataFactory {
    private TestBlochDataFactory() {}

    private static PulseStep step(double b1x, double b1y, double gx, double gz, double rfGate) {
        return new PulseStep(new double[]{b1x, b1y, gx, gz}, rfGate);
    }

    public static BlochData sampleDocument() {
        return new BlochData(sampleField());
    }

    public static BlochData brokenDocumentMissingSegments() {
        var field = sampleField();
        field.segments = null;
        return new BlochData(field);
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
        return new BlochData(incoherentField());
    }

    public static List<PulseSegment> freePrecessionPulse() {
        return List.of(new PulseSegment(List.of(step(0, 0, 0, 0, 0))));
    }

    private static FieldMap sampleField() {
        var field = new FieldMap();
        field.rMm = new double[]{0, 15, 30};
        field.zMm = new double[]{-10, 0, 10};
        field.b0Ref = 1.5;
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
        field.circuit = buildTestCircuit(field.rMm, field.zMm, field.b0Ref, field.fovX, field.fovZ);
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
        field.segments = List.of(new Segment(1.0e-6, 1, 0));
        field.circuit = buildTestCircuit(field.rMm, field.zMm, field.b0Ref, field.fovX, field.fovZ);
        return field;
    }

    /** Build a three-coil, one-probe compiled circuit with deterministic eigenfields. */
    public static CompiledCircuit buildTestCircuit(double[] rMm, double[] zMm, double b0, double fovX, double fovZ) {
        int nR = rMm.length;
        int nZ = zMm.length;
        double fovXHalf = Math.max(fovX / 2, 1e-9);
        double fovZHalf = Math.max(fovZ / 2, 1e-9);
        double[][] rfEx = new double[nR][nZ];
        double[][] rfEy = new double[nR][nZ];
        double[][] rfEz = new double[nR][nZ];
        double[][] gxEx = new double[nR][nZ];
        double[][] gxEy = new double[nR][nZ];
        double[][] gxEz = new double[nR][nZ];
        double[][] gzEx = new double[nR][nZ];
        double[][] gzEy = new double[nR][nZ];
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

        var rfCoilId = new ComponentId("coil-rf");
        var gxCoilId = new ComponentId("coil-gx");
        var gzCoilId = new ComponentId("coil-gz");
        var rfSrcId = new ComponentId("src-rf");
        var gxSrcId = new ComponentId("src-gx");
        var gzSrcId = new ComponentId("src-gz");
        var probeId = new ComponentId("probe-rx");

        var sources = List.of(
            new CompiledSource(rfSrcId, "RF", 0, AmplitudeKind.QUADRATURE, 0.0, 0.0),
            new CompiledSource(gxSrcId, "Gx", 2, AmplitudeKind.REAL, 0.0, 0.0),
            new CompiledSource(gzSrcId, "Gz", 3, AmplitudeKind.REAL, 0.0, 0.0)
        );
        var coils = List.of(
            new CompiledCoil(rfCoilId, "RF Coil", 0, 0, rfEx, rfEy, rfEz),
            new CompiledCoil(gxCoilId, "Gx Coil", 0, 0, gxEx, gxEy, gxEz),
            new CompiledCoil(gzCoilId, "Gz Coil", 0, 0, gzEx, gzEy, gzEz)
        );
        var probes = List.of(
            new CompiledProbe(probeId, "Primary RX", 1.0, 0.0, Double.POSITIVE_INFINITY)
        );
        var drives = List.of(
            new TopologyLink(0, 0, List.of(), true),
            new TopologyLink(1, 1, List.of(), true),
            new TopologyLink(2, 2, List.of(), true)
        );
        var observes = List.of(new TopologyLink(0, 0, List.of(), true));

        return new CompiledCircuit(
            sources, List.of(), coils, probes,
            drives, observes,
            List.of(), List.of(), List.of(),
            /* totalChannelCount = */ 4);
    }

    private static double sq(double v) { return v * v; }
}
