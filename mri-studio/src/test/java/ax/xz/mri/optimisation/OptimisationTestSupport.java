package ax.xz.mri.optimisation;

import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.service.circuit.CompiledCircuit;
import ax.xz.mri.service.circuit.CompiledCircuit.CompiledCoil;
import ax.xz.mri.service.circuit.CompiledCircuit.CompiledProbe;
import ax.xz.mri.service.circuit.CompiledCircuit.CompiledSource;
import ax.xz.mri.service.circuit.CompiledCircuit.TopologyLink;
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

    /**
     * Finite-train template with per-step control layout = 4 channels + rfGate = 5 scalars.
     */
    public static SequenceTemplate finiteTemplateFor(List<PulseSegment> pulse) {
        return SequenceTemplate.finiteTrain(pulse.stream()
            .map(segment -> new ControlSegmentSpec(1e-6, segment.steps().size(), 0, 5))
            .toList());
    }

    /**
     * Single-point geometry for simple objective tests. Uses a single-coil,
     * single-probe circuit where the RF coil's eigenfield is {@code
     * Vec3.of(1,0,0)} and the probe observes it with the given {@code
     * rxWeight} gain. Signal reduces to {@code rxWeight · Mx}.
     */
    public static ProblemGeometry singlePointGeometry(double rxWeight, double outWeight) {
        var rfSrcId = new ComponentId("src-rf");
        var gxSrcId = new ComponentId("src-gx");
        var gzSrcId = new ComponentId("src-gz");
        var rfCoilId = new ComponentId("coil-rf");
        var probeId = new ComponentId("probe-rx");

        // RF coil with Ex=1 at the one grid point; Gx/Gz coils are placeholders with zero field.
        double[][] rfEx = {{1.0}};
        double[][] zeros = {{0.0}};
        var sources = List.of(
            new CompiledSource(rfSrcId, "RF", 0, AmplitudeKind.QUADRATURE, 0.0, 0.0),
            new CompiledSource(gxSrcId, "Gx", 2, AmplitudeKind.REAL, 0.0, 0.0),
            new CompiledSource(gzSrcId, "Gz", 3, AmplitudeKind.REAL, 0.0, 0.0)
        );
        var coils = List.of(
            new CompiledCoil(rfCoilId, "RF Coil", 0, 0, rfEx, zeros, zeros),
            new CompiledCoil(new ComponentId("coil-gx"), "Gx Coil", 0, 0, zeros, zeros, zeros),
            new CompiledCoil(new ComponentId("coil-gz"), "Gz Coil", 0, 0, zeros, zeros, zeros)
        );
        var probes = List.of(new CompiledProbe(probeId, "Primary RX", rxWeight, 0.0, 0.0, Double.POSITIVE_INFINITY));
        var drives = List.of(
            new TopologyLink(0, 0, List.of(), true),
            new TopologyLink(1, 1, List.of(), true),
            new TopologyLink(2, 2, List.of(), true)
        );
        var observes = List.of(new TopologyLink(0, 0, List.of(), true));
        var circuit = new CompiledCircuit(sources, List.of(), coils, probes,
            drives, observes, List.of(), List.of(), List.of(), 4);

        int n = 1;
        return new ProblemGeometry(
            new double[]{0.0},                 // mx0
            new double[]{0.0},                 // my0
            new double[]{1.0},                 // mz0
            new double[]{0.0},                 // staticBz
            new double[]{outWeight},           // wOut
            new double[][]{{1.0}, {0.0}, {0.0}},   // coilExFlat
            new double[][]{{0.0}, {0.0}, {0.0}},   // coilEyFlat
            new double[][]{{0.0}, {0.0}, {0.0}},   // coilEzFlat
            circuit,
            1.0,   // gamma
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
