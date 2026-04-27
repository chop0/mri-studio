package ax.xz.mri.support;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.Wire;
import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.scenario.SimulationOutput;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.service.circuit.CircuitCompiler;
import ax.xz.mri.service.circuit.CompiledCircuit;

import java.util.List;

/**
 * Small deterministic Bloch documents used by unit tests.
 *
 * <p>Channel layout: controls = {@code [rf_I, rf_Q, gx, gz]}. The test
 * circuit has three drive sources (RF QUADRATURE, Gx REAL, Gz REAL), three
 * coils (each with a scripted eigenfield shape), and one probe wired to the
 * RF coil for receive. Construction goes through the real
 * {@link CircuitCompiler} so tests exercise production code paths.
 */
public final class TestSimulationOutputFactory {
    private TestSimulationOutputFactory() {}

    private static PulseStep step(double b1x, double b1y, double gx, double gz, double rfGate) {
        return new PulseStep(new double[]{b1x, b1y, gx, gz}, rfGate);
    }

    public static SimulationOutput sampleDocument() {
        return new SimulationOutput(sampleField());
    }

    public static SimulationOutput brokenDocumentMissingSegments() {
        var field = sampleField();
        field.segments = null;
        return new SimulationOutput(field);
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

    public static SimulationOutput incoherentTransverseDocument() {
        return new SimulationOutput(incoherentField());
    }

    public static List<PulseSegment> freePrecessionPulse() {
        return List.of(new PulseSegment(List.of(step(0, 0, 0, 0, 0))));
    }

    private static FieldMap sampleField() {
        var field = new FieldMap();
        field.rMm = new double[]{0, 15, 30};
        field.zMm = new double[]{-10, 0, 10};
        field.b0Ref = 1.5;
        field.staticBz = new double[3][3];
        for (int r = 0; r < 3; r++) for (int z = 0; z < 3; z++) field.staticBz[r][z] = -field.b0Ref;
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
        field.circuit = buildTestCircuit(field.rMm, field.zMm);
        return field;
    }

    private static FieldMap incoherentField() {
        var field = new FieldMap();
        field.rMm = new double[]{0, 15, 30};
        field.zMm = new double[]{-10, 0, 10};
        field.b0Ref = 1.5;
        field.staticBz = new double[3][3];
        for (int r = 0; r < 3; r++) for (int z = 0; z < 3; z++) field.staticBz[r][z] = -field.b0Ref;
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
        field.circuit = buildTestCircuit(field.rMm, field.zMm);
        return field;
    }

    /**
     * Build a three-coil, one-probe compiled circuit. Eigenfields:
     * <ul>
     *   <li>RF coil: uniform transverse {@code Vec3.of(1, 0, 0)}</li>
     *   <li>Gx coil: linear {@code Vec3.of(0, 0, x)}</li>
     *   <li>Gz coil: linear {@code Vec3.of(0, 0, z)}</li>
     * </ul>
     * Each source drives its own coil directly — no switches, no mux.
     */
    public static CompiledCircuit buildTestCircuit(double[] rMm, double[] zMm) {
        var repo = ProjectRepository.untitled();
        var rfEfId = addEigenfield(repo, "ef-rf", "return Vec3.of(1, 0, 0);");
        var gxEfId = addEigenfield(repo, "ef-gx", "return Vec3.of(0, 0, x);");
        var gzEfId = addEigenfield(repo, "ef-gz", "return Vec3.of(0, 0, z);");

        // RF drive is two REAL envelopes fed into a Modulator (loHz=0 —
        // keep the carrier out of the test so optimiser math stays simple;
        // the Modulator still exercises the coupled I/Q stamp path).
        var rfISrc = new CircuitComponent.VoltageSource(new ComponentId("src-rf-i"),
            "RF I", AmplitudeKind.REAL, 0, 0, 1, 0);
        var rfQSrc = new CircuitComponent.VoltageSource(new ComponentId("src-rf-q"),
            "RF Q", AmplitudeKind.REAL, 0, 0, 1, 0);
        var gxSrc = new CircuitComponent.VoltageSource(new ComponentId("src-gx"),
            "Gx", AmplitudeKind.REAL, 0, -1, 1, 0);
        var gzSrc = new CircuitComponent.VoltageSource(new ComponentId("src-gz"),
            "Gz", AmplitudeKind.REAL, 0, -1, 1, 0);
        var rfModulator = new CircuitComponent.Modulator(new ComponentId("mod-rf"),
            "RF Mod", 0);
        var rfCoil = new CircuitComponent.Coil(new ComponentId("coil-rf"), "RF Coil", rfEfId, 0, 1);
        var gxCoil = new CircuitComponent.Coil(new ComponentId("coil-gx"), "Gx Coil", gxEfId, 0, 1);
        var gzCoil = new CircuitComponent.Coil(new ComponentId("coil-gz"), "Gz Coil", gzEfId, 0, 1);
        var probe = new CircuitComponent.Probe(new ComponentId("probe-rx"),
            "Primary RX", 1.0, 0.0, Double.POSITIVE_INFINITY);

        var wires = List.of(
            new Wire("w-rfi", new ComponentTerminal(rfISrc.id(), "out"), new ComponentTerminal(rfModulator.id(), "in0")),
            new Wire("w-rfq", new ComponentTerminal(rfQSrc.id(), "out"), new ComponentTerminal(rfModulator.id(), "in1")),
            new Wire("w-rf", new ComponentTerminal(rfModulator.id(), "out"), new ComponentTerminal(rfCoil.id(), "in")),
            new Wire("w-gx", new ComponentTerminal(gxSrc.id(), "out"), new ComponentTerminal(gxCoil.id(), "in")),
            new Wire("w-gz", new ComponentTerminal(gzSrc.id(), "out"), new ComponentTerminal(gzCoil.id(), "in")),
            new Wire("w-probe", new ComponentTerminal(probe.id(), "in"), new ComponentTerminal(rfCoil.id(), "in"))
        );
        var doc = new CircuitDocument(new ProjectNodeId("circuit-test"), "Test",
            List.of(rfISrc, rfQSrc, gxSrc, gzSrc, rfModulator, rfCoil, gxCoil, gzCoil, probe),
            wires, CircuitLayout.empty());

        return CircuitCompiler.compile(doc, repo, rMm, zMm);
    }

    private static ProjectNodeId addEigenfield(ProjectRepository repo, String id, String script) {
        var nodeId = new ProjectNodeId(id);
        repo.addEigenfield(new EigenfieldDocument(nodeId, id, "", script, "T"));
        return nodeId;
    }
}
