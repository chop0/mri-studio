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
import ax.xz.mri.model.simulation.BlochDataFactory;
import ax.xz.mri.model.simulation.FieldSymmetry;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for explicit switch-gated acquisition (the M3 milestone) and basic
 * symmetry declarations on eigenfields.
 *
 * <p>Under the circuit model there's no more implicit "rfGate" hint driving the
 * receive path; a probe only observes the signal when every switch on its
 * topology link to a coil is closed. A GATE voltage source wired to a switch's
 * {@code ctl} port opens/closes the path.
 */
class AcquisitionGateAndSymmetryTest {

    private static final double GAMMA = 267.522e6;
    private static final double DT = 1e-6;
    private static final double B0 = 0.0154;

    private static ProjectNodeId addEigenfield(ProjectRepository repo, String name, String script, FieldSymmetry sym) {
        var id = new ProjectNodeId("ef-" + name);
        repo.addEigenfield(new EigenfieldDocument(id, name, "", script, "T", 1.0, sym));
        return id;
    }

    @Test
    void eigenfieldSymmetryDefaultsToAxisymmetric() {
        var doc = new EigenfieldDocument(new ProjectNodeId("ef-0"),
            "B0", "", "return Vec3.of(0, 0, 1);", "T", 1.0);
        assertEquals(FieldSymmetry.AXISYMMETRIC_Z, doc.symmetry());
    }

    @Test
    void eigenfieldSymmetrySurvivesWithMutators() {
        var doc = new EigenfieldDocument(new ProjectNodeId("ef-1"),
            "Gx", "", "return Vec3.of(0, 0, x);", "T", 1.0, FieldSymmetry.CARTESIAN_3D);
        assertEquals(FieldSymmetry.CARTESIAN_3D, doc.symmetry());
        assertEquals(FieldSymmetry.CARTESIAN_3D, doc.withName("Renamed").symmetry());
        assertEquals(FieldSymmetry.AXISYMMETRIC_Z,
            doc.withSymmetry(FieldSymmetry.AXISYMMETRIC_Z).symmetry());
    }

    @Test
    void gateSourceControlsSwitchClosure() {
        var repo = ProjectRepository.untitled();
        var b0Id = addEigenfield(repo, "B0", "return Vec3.of(0, 0, 1);", FieldSymmetry.AXISYMMETRIC_Z);
        var rxId = addEigenfield(repo, "rx", "return Vec3.of(1, 0, 0);", FieldSymmetry.AXISYMMETRIC_Z);

        var b0Src = new CircuitComponent.VoltageSource(new ComponentId("src-b0"),
            "B0", AmplitudeKind.STATIC, 0, 0, B0, 0);
        var rxGate = new CircuitComponent.VoltageSource(new ComponentId("src-rxgate"),
            "RX Gate", AmplitudeKind.GATE, 0, 0, 1, 0);
        var b0Coil = new CircuitComponent.Coil(new ComponentId("coil-b0"), "B0 Coil", b0Id, 0, 0);
        var rxCoil = new CircuitComponent.Coil(new ComponentId("coil-rx"), "RX Coil", rxId, 0, 0);
        var rxSwitch = new CircuitComponent.SwitchComponent(
            new ComponentId("sw-rx"), "RX Switch", 0.5, 1e9, 0.5);
        var probe = new CircuitComponent.Probe(new ComponentId("probe-rx"),
            "RX", 1.0, 0.0, Double.POSITIVE_INFINITY);
        var gnd = new CircuitComponent.Ground(new ComponentId("gnd-0"), "GND");

        var wires = new ArrayList<Wire>();
        wires.add(new Wire("w-b0-out", new ComponentTerminal(b0Src.id(), "out"), new ComponentTerminal(b0Coil.id(), "a")));
        wires.add(new Wire("w-b0-g",   new ComponentTerminal(b0Coil.id(), "b"), new ComponentTerminal(gnd.id(), "a")));
        wires.add(new Wire("w-probe-in", new ComponentTerminal(probe.id(), "in"), new ComponentTerminal(rxSwitch.id(), "a")));
        wires.add(new Wire("w-probe-sw", new ComponentTerminal(rxSwitch.id(), "b"), new ComponentTerminal(rxCoil.id(), "a")));
        wires.add(new Wire("w-rx-g",     new ComponentTerminal(rxCoil.id(), "b"), new ComponentTerminal(gnd.id(), "a")));
        wires.add(new Wire("w-ctl-out",  new ComponentTerminal(rxGate.id(), "out"), new ComponentTerminal(rxSwitch.id(), "ctl")));

        var circuitId = new ProjectNodeId("circuit-0");
        var circuit = new CircuitDocument(circuitId, "Test",
            List.of(b0Src, rxGate, b0Coil, rxCoil, rxSwitch, probe, gnd),
            wires, CircuitLayout.empty());
        repo.addCircuit(circuit);

        var cfg = new SimulationConfig(1000, 1000, GAMMA, 5, 20, 10, 3, 3, B0, DT, circuitId);

        // Two segments: gate open (ctl >= threshold) then closed. Controls layout: [rx_gate].
        var firstHalf = new PulseSegment(filled(10, 1.0));
        var secondHalf = new PulseSegment(filled(10, 0.0));
        var segments = List.of(new Segment(DT, 10, 0), new Segment(DT, 10, 0));

        var data = BlochDataFactory.build(cfg, segments, repo);
        var signals = SignalTraceComputer.computeAll(data, List.of(firstHalf, secondHalf));
        assertNotNull(signals);
        assertFalse(signals.byProbe().isEmpty(), "Probe should emit a trace");

        var trace = signals.byProbe().values().iterator().next();
        // When the gate goes low, the probe must report (0, 0).
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
