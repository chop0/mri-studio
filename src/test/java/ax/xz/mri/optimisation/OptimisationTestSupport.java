package ax.xz.mri.optimisation;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.Wire;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.service.circuit.CircuitCompiler;
import ax.xz.mri.support.TestBlochDataFactory;

import java.util.List;

public final class OptimisationTestSupport {
    private OptimisationTestSupport() {}

    public static BlochData sampleDocument() {
        return TestBlochDataFactory.sampleDocument();
    }

    public static List<PulseSegment> pulseA() {
        return TestBlochDataFactory.pulseA();
    }

    public static List<PulseSegment> pulseB() {
        return TestBlochDataFactory.pulseB();
    }

    public static SequenceTemplate finiteTemplateFor(List<PulseSegment> pulse) {
        return SequenceTemplate.finiteTrain(pulse.stream()
            .map(segment -> new ControlSegmentSpec(1e-6, segment.steps().size(), 0, 5))
            .toList());
    }

    /**
     * Single-point geometry for simple objective tests. Three drive sources
     * wired one-to-one to three coils (RF, Gx, Gz), sampled on a 1×1 grid.
     * The RF coil's eigenfield is the dimensionless shape {@code Vec3.of(1, 0, 0)};
     * its {@code sensitivityT_per_A = rxWeight} so the signal reduces to
     * {@code rxWeight · Mx}. Probe is wired to the RF coil directly; no mux.
     */
    public static ProblemGeometry singlePointGeometry(double rxWeight, double outWeight) {
        var repo = ProjectRepository.untitled();
        var rfEfId = new ProjectNodeId("ef-rf");
        repo.addEigenfield(new EigenfieldDocument(rfEfId, "ef-rf", "",
            "return Vec3.of(1, 0, 0);", "T"));
        var zeroEfId = new ProjectNodeId("ef-zero");
        repo.addEigenfield(new EigenfieldDocument(zeroEfId, "ef-zero", "",
            "return Vec3.ZERO;", "T"));

        // RF drive is two REAL envelopes (I and Q) piped through a
        // Modulator at loHz=0 — the sources appear as "RF I" / "RF Q" in
        // the compiled circuit, so optimisers see two control channels
        // that together drive the coil.
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
        // RF coil's sensitivity carries the rxWeight calibration. Other coils
        // get a default sensitivity of 1 (their eigenfield is zero anyway).
        // R = 1 Ω so the MNA has a finite I↔V relation per the new Coil
        // contract; the absolute value cancels in the optimiser-side weight
        // chain that produced these tests.
        var rfCoil = new CircuitComponent.Coil(new ComponentId("coil-rf"), "RF Coil", rfEfId,
            0, 1, Math.max(rxWeight, 1e-30));
        var gxCoil = new CircuitComponent.Coil(new ComponentId("coil-gx"), "Gx Coil", zeroEfId, 0, 1);
        var gzCoil = new CircuitComponent.Coil(new ComponentId("coil-gz"), "Gz Coil", zeroEfId, 0, 1);
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
        var doc = new CircuitDocument(new ProjectNodeId("circuit-test"), "test",
            List.of(rfISrc, rfQSrc, gxSrc, gzSrc, rfModulator, rfCoil, gxCoil, gzCoil, probe),
            wires, CircuitLayout.empty());
        var circuit = CircuitCompiler.compile(doc, repo, new double[]{0}, new double[]{0});

        int n = 1;
        return new ProblemGeometry(
            new double[]{0.0},                 // mx0
            new double[]{0.0},                 // my0
            new double[]{1.0},                 // mz0
            new double[]{0.0},                 // staticBz
            new double[]{outWeight},           // wOut
            new double[][]{{rxWeight}, {0.0}, {0.0}},  // coilExFlat
            new double[][]{{0.0}, {0.0}, {0.0}},       // coilEyFlat
            new double[][]{{0.0}, {0.0}, {0.0}},       // coilEzFlat
            circuit,
            1.0,   // gamma
            0.0,   // b0Ref
            1.0,   // t1
            1.0,   // t2
            n, n);
    }

    public static OptimisationProblem simpleProblem(SequenceTemplate template, ObjectiveSpec objectiveSpec) {
        return new OptimisationProblem(singlePointGeometry(1.0, 0.0), template, objectiveSpec);
    }

    public static ObjectiveSpec simpleObjective(ObjectiveMode mode) {
        return new ObjectiveSpec(mode, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }
}
