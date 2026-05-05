package ax.xz.mri.service.simulation;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.Wire;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.SimulationOutputFactory;
import ax.xz.mri.model.simulation.FieldSymmetry;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.state.ProjectState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for explicit switch-gated acquisition and basic symmetry declarations
 * on eigenfields. A probe only observes when every switch on its topology
 * link is closed.
 */
class AcquisitionGateAndSymmetryTest {

    private static final double GAMMA = 267.522e6;
    private static final double DT = 1e-6;
    private static final double B0 = 0.0154;

    private static ProjectNodeId addEigenfield(ProjectState repo, String name, String script, FieldSymmetry sym) {
        var id = new ProjectNodeId("ef-" + name);
        repo = repo.withEigenfield(new EigenfieldDocument(id, name, "", script, "T", sym));
        return id;
    }

    @Test
    void eigenfieldSymmetryDefaultsToAxisymmetric() {
        var doc = new EigenfieldDocument(new ProjectNodeId("ef-0"),
            "B0", "", "return Vec3.of(0, 0, 1);", "T");
        assertEquals(FieldSymmetry.AXISYMMETRIC_Z, doc.symmetry());
    }

    @Test
    void eigenfieldSymmetrySurvivesWithMutators() {
        var doc = new EigenfieldDocument(new ProjectNodeId("ef-1"),
            "Gx", "", "return Vec3.of(0, 0, x);", "T", FieldSymmetry.CARTESIAN_3D);
        assertEquals(FieldSymmetry.CARTESIAN_3D, doc.symmetry());
        assertEquals(FieldSymmetry.CARTESIAN_3D, doc.withName("Renamed").symmetry());
        assertEquals(FieldSymmetry.AXISYMMETRIC_Z,
            doc.withSymmetry(FieldSymmetry.AXISYMMETRIC_Z).symmetry());
    }

    @Test
    void gateSourceControlsSwitchClosure() {
        var repo = ProjectState.empty();
		var b0Id = new ProjectNodeId("ef-B0");
		var rxId = new ProjectNodeId("ef-rx");

		repo = repo.withEigenfield(new EigenfieldDocument(b0Id, "B0", "", "return Vec3.of(0, 0, 1);", "T", FieldSymmetry.AXISYMMETRIC_Z));
		repo = repo.withEigenfield(new EigenfieldDocument(rxId, "rx", "", "return Vec3.of(1, 0, 0);", "T", FieldSymmetry.AXISYMMETRIC_Z));

        var b0Src = new CircuitComponent.VoltageSource(new ComponentId("src-b0"),
            "B0", AmplitudeKind.STATIC, 0, 0, B0, 0);
        var rxGate = new CircuitComponent.VoltageSource(new ComponentId("src-rxgate"),
            "RX Gate", AmplitudeKind.GATE, 0, 0, 1, 0);
        var b0Coil = new CircuitComponent.Coil(new ComponentId("coil-b0"), "B0 Coil", b0Id, 0, 1);
        var rxCoil = new CircuitComponent.Coil(new ComponentId("coil-rx"), "RX Coil", rxId, 0, 1);
        var sw = new CircuitComponent.SwitchComponent(new ComponentId("sw"), "RX Switch", 0.5, 1e9, 0.5);
        var probe = new CircuitComponent.Probe(new ComponentId("probe"), "RX", 1, 0, Double.POSITIVE_INFINITY);

        var wires = new ArrayList<Wire>();
        wires.add(new Wire("w-b0",        new ComponentTerminal(b0Src.id(), "out"), new ComponentTerminal(b0Coil.id(), "in")));
        wires.add(new Wire("w-probe-in",  new ComponentTerminal(probe.id(), "in"), new ComponentTerminal(sw.id(), "a")));
        wires.add(new Wire("w-probe-sw",  new ComponentTerminal(sw.id(), "b"), new ComponentTerminal(rxCoil.id(), "in")));
        wires.add(new Wire("w-ctl",       new ComponentTerminal(rxGate.id(), "out"), new ComponentTerminal(sw.id(), "ctl")));

        var circuitId = new ProjectNodeId("circuit-0");
        var circuit = new CircuitDocument(circuitId, "Test",
            List.of(b0Src, rxGate, b0Coil, rxCoil, sw, probe), wires, CircuitLayout.empty());
        repo = repo.withCircuit(circuit);

        var cfg = new SimulationConfig(1000, 1000, GAMMA, 5, 20, 10, 3, 3, B0, DT, circuitId);

        var firstHalf = new PulseSegment(filled(10, 1.0));
        var secondHalf = new PulseSegment(filled(10, 0.0));
        var segments = List.of(new Segment(DT, 10, 0), new Segment(DT, 10, 0));

        var data = SimulationOutputFactory.build(cfg, segments, repo);
        var signals = SignalTraceComputer.computeAll(data, List.of(firstHalf, secondHalf));
        assertNotNull(signals);
        assertFalse(signals.byProbe().isEmpty(), "Probe should emit a trace");

        var trace = signals.byProbe().values().iterator().next();
        var closedPhase = trace.points().stream()
            .filter(p -> p.tMicros() >= 10 * DT * 1e6)
            .toList();
        assertFalse(closedPhase.isEmpty(), "Second segment should emit samples");
        for (var p : closedPhase) {
            assertEquals(0.0, p.real(), 1e-9, "Probe must be zero while RX switch is open");
            assertEquals(0.0, p.imag(), 1e-9, "Probe must be zero while RX switch is open");
        }
    }

    private static List<PulseStep> filled(int count, double gateValue) {
        var steps = new ArrayList<PulseStep>(count);
        for (int i = 0; i < count; i++) steps.add(new PulseStep(new double[]{gateValue}, 0.0));
        return steps;
    }
}
