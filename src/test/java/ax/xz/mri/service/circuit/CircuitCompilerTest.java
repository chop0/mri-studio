package ax.xz.mri.service.circuit;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.Wire;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Topology-resolution tests for {@link CircuitCompiler}. Sources and probes
 * are single-terminal; coils are single-terminal (implicit ground on the
 * return).
 */
class CircuitCompilerTest {

    private static final double[] R = {0, 10};
    private static final double[] Z = {-10, 10};

    @Test
    void multipleSourcesResolveToTheirOwnCoils() {
        var b0Src = voltageSource("src-b0", "B0", AmplitudeKind.STATIC, 1.5);
        var rfSrc = voltageSource("src-rf", "RF", AmplitudeKind.QUADRATURE, 0.001);
        var b0Coil = new CircuitComponent.Coil(new ComponentId("coil-b0"), "B0 Coil", null, 0, 0);
        var rfCoil = new CircuitComponent.Coil(new ComponentId("coil-rf"), "RF Coil", null, 0, 0);

        var wires = List.of(
            wire("w-b0", b0Src.id(), "out", b0Coil.id(), "in"),
            wire("w-rf", rfSrc.id(), "out", rfCoil.id(), "in")
        );
        var circuit = new CircuitDocument(new ProjectNodeId("c-0"), "c",
            List.of(b0Src, rfSrc, b0Coil, rfCoil), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(circuit, ProjectRepository.untitled(), R, Z);

        assertEquals(2, compiled.drives().size(), "Both sources resolve");
        assertEquals(0, compiled.drives().get(0).coilIndex());
        assertEquals(1, compiled.drives().get(1).coilIndex());
        for (var link : compiled.drives()) assertTrue(link.forwardPolarity());
    }

    @Test
    void switchOnDrivePath_appearsInTopologyLink() {
        var rfSrc = voltageSource("src-rf", "RF", AmplitudeKind.QUADRATURE, 0.001);
        var gate = voltageSource("src-gate", "Gate", AmplitudeKind.GATE, 1);
        var sw = new CircuitComponent.SwitchComponent(new ComponentId("sw"), "Sw", 0.5, 1e9, 0.5);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "Coil", null, 0, 0);

        var wires = List.of(
            wire("w1", rfSrc.id(), "out", sw.id(), "a"),
            wire("w2", sw.id(), "b", coil.id(), "in"),
            wire("w3", gate.id(), "out", sw.id(), "ctl")
        );
        var circuit = new CircuitDocument(new ProjectNodeId("c-1"), "c",
            List.of(rfSrc, gate, sw, coil), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(circuit, ProjectRepository.untitled(), R, Z);

        assertEquals(1, compiled.drives().size());
        var rfLink = compiled.drives().get(0);
        assertEquals(1, rfLink.switchIndices().size(), "switch on path");
        assertEquals(1, compiled.switches().get(0).ctlSourceIndex(), "ctl resolves to gate source");
        assertFalse(compiled.switches().get(0).ctlViaActive());
    }

    @Test
    void switchCtlViaSourceActivePort_isDetected() {
        var rfSrc = voltageSource("src-rf", "RF", AmplitudeKind.QUADRATURE, 0.001);
        var sw = new CircuitComponent.SwitchComponent(new ComponentId("sw"), "Sw", 0.5, 1e9, 0.5);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "Coil", null, 0, 0);
        var wires = List.of(
            wire("w1", rfSrc.id(), "out", sw.id(), "a"),
            wire("w2", sw.id(), "b", coil.id(), "in"),
            wire("w3", rfSrc.id(), "active", sw.id(), "ctl")
        );
        var circuit = new CircuitDocument(new ProjectNodeId("c-2"), "c",
            List.of(rfSrc, sw, coil), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(circuit, ProjectRepository.untitled(), R, Z);
        assertEquals(0, compiled.switches().get(0).ctlSourceIndex());
        assertTrue(compiled.switches().get(0).ctlViaActive(),
            "ctl wired to a source's 'active' port routes via the active signal");
    }

    @Test
    void floatingSwitchControl_resolvesAsPermanentlyOpen() {
        var src = voltageSource("src-rf", "RF", AmplitudeKind.REAL, 1);
        var sw = new CircuitComponent.SwitchComponent(new ComponentId("sw"), "Sw", 0.5, 1e9, 0.5);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "Coil", null, 0, 0);
        var wires = List.of(
            wire("w1", src.id(), "out", sw.id(), "a"),
            wire("w2", sw.id(), "b", coil.id(), "in")
        );
        var circuit = new CircuitDocument(new ProjectNodeId("c-3"), "c",
            List.of(src, sw, coil), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(circuit, ProjectRepository.untitled(), R, Z);
        assertEquals(-1, compiled.switches().get(0).ctlSourceIndex());
    }

    @Test
    void probeThroughSwitchResolvesAsObserveLink() {
        var rfSrc = voltageSource("src-rf", "RF", AmplitudeKind.QUADRATURE, 0.001);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "Coil", null, 0, 0);
        var sw = new CircuitComponent.SwitchComponent(new ComponentId("sw"), "Sw", 0.5, 1e9, 0.5);
        var probe = new CircuitComponent.Probe(new ComponentId("probe"), "RX", 1, 0, Double.POSITIVE_INFINITY);

        var wires = List.of(
            wire("w1", rfSrc.id(), "out", coil.id(), "in"),
            wire("w2", probe.id(), "in", sw.id(), "a"),
            wire("w3", sw.id(), "b", coil.id(), "in"),
            wire("w4", rfSrc.id(), "active", sw.id(), "ctl")
        );
        var circuit = new CircuitDocument(new ProjectNodeId("c-4"), "c",
            List.of(rfSrc, coil, sw, probe), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(circuit, ProjectRepository.untitled(), R, Z);

        assertEquals(1, compiled.observes().size(), "probe produces one observe link");
        var obs = compiled.observes().get(0);
        assertEquals(0, obs.coilIndex());
        assertEquals(1, obs.switchIndices().size());
    }

    private static CircuitComponent.VoltageSource voltageSource(
        String id, String name, AmplitudeKind kind, double amp
    ) {
        return new CircuitComponent.VoltageSource(new ComponentId(id), name, kind, 0, 0, amp, 0);
    }

    private static Wire wire(String id, ComponentId a, String ap, ComponentId b, String bp) {
        return new Wire(id, new ComponentTerminal(a, ap), new ComponentTerminal(b, bp));
    }
}
