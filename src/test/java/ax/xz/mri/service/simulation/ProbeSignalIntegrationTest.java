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
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end probe-signal test: a gated probe should be silent while its
 * switch is open.
 */
class ProbeSignalIntegrationTest {

    private static final double GAMMA = 267.522e6;
    private static final double DT = 1e-6;
    private static final double B0 = 0.0154;

    @Test
    void gatedProbeIsSilentWhenSwitchOpen() {
        var repo = ProjectRepository.untitled();
        var rxEf = addEigenfield(repo, "rx", "return Vec3.of(1, 0, 0);");
        var b0Ef = addEigenfield(repo, "b0", "return Vec3.of(0, 0, 1);");

        var b0Src = voltageSource("src-b0", "B0", AmplitudeKind.STATIC, 0, B0);
        var gate = voltageSource("src-gate", "RX Gate", AmplitudeKind.GATE, 0, 1);
        var b0Coil = new CircuitComponent.Coil(new ComponentId("coil-b0"), "B0 Coil", b0Ef, 0, 1);
        var rxCoil = new CircuitComponent.Coil(new ComponentId("coil-rx"), "RX Coil", rxEf, 0, 1);
        var sw = new CircuitComponent.SwitchComponent(new ComponentId("sw"), "SW", 0.5, 1e9, 0.5);
        var probe = new CircuitComponent.Probe(new ComponentId("probe"), "RX", 1, 0, Double.POSITIVE_INFINITY);

        var wires = List.of(
            wire("w-b0", b0Src.id(), "out", b0Coil.id(), "in"),
            // Probe observes RX coil through the switch.
            wire("w-probe-in", probe.id(), "in", sw.id(), "a"),
            wire("w-probe-sw", sw.id(), "b", rxCoil.id(), "in"),
            // Gate drives the switch (positive: closed when gate == 1).
            wire("w-ctl", gate.id(), "out", sw.id(), "ctl")
        );
        var circuitId = new ProjectNodeId("c");
        var circuit = new CircuitDocument(circuitId, "T",
            List.of(b0Src, gate, b0Coil, rxCoil, sw, probe), wires, CircuitLayout.empty());
        repo.addCircuit(circuit);

        var config = new SimulationConfig(1000, 1000, GAMMA, 5, 20, 10, 3, 3, B0, DT, circuitId);
        var steps = new ArrayList<PulseStep>();
        for (int i = 0; i < 10; i++) steps.add(new PulseStep(new double[]{1.0}, 0.0));
        for (int i = 0; i < 10; i++) steps.add(new PulseStep(new double[]{0.0}, 0.0));
        var pulse = List.of(new PulseSegment(steps));
        var segments = List.of(new Segment(DT, 20, 0));

        var data = SimulationOutputFactory.build(config, segments, repo);
        var signals = SignalTraceComputer.computeAll(data, pulse);
        var trace = signals.byProbe().get("RX");
        assertNotNull(trace);

        int closed = 0;
        int open = 0;
        for (var p : trace.points()) {
            if (p.tMicros() < 10 * DT * 1e6) {
                closed++;
                assertFalse(Double.isNaN(p.real()));
                assertFalse(Double.isNaN(p.imag()));
            } else {
                open++;
                assertEquals(0.0, p.real(), 1e-9, "probe silent when switch open");
                assertEquals(0.0, p.imag(), 1e-9, "probe silent when switch open");
            }
        }
        assertTrue(closed > 0);
        assertTrue(open > 0);
    }

    private static CircuitComponent.VoltageSource voltageSource(String id, String name,
                                                                AmplitudeKind kind,
                                                                double minA, double maxA) {
        return new CircuitComponent.VoltageSource(new ComponentId(id), name, kind, 0, minA, maxA, 0);
    }

    private static Wire wire(String id, ComponentId a, String ap, ComponentId b, String bp) {
        return new Wire(id, new ComponentTerminal(a, ap), new ComponentTerminal(b, bp));
    }

    private static ProjectNodeId addEigenfield(ProjectRepository repo, String name, String script) {
        var id = new ProjectNodeId("ef-" + name);
        repo.addEigenfield(new EigenfieldDocument(id, name, "", script, "T"));
        return id;
    }
}
