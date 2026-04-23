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
 * Topology-resolution tests for {@link CircuitCompiler}.
 *
 * <p>Sources and probes are single-terminal; the compiler must see their
 * implicit return on the ground net. These tests exercise the edges: shared
 * ground disambiguation, switch gating, coil not returning to ground,
 * floating switch control.
 */
class CircuitCompilerTest {

    private static final double[] R = {0, 10};
    private static final double[] Z = {-10, 10};

    @Test
    void twoSourcesShareGroundAndEachResolveToCorrectCoil() {
        var b0Src = voltageSource("src-b0", "B0", AmplitudeKind.STATIC, 1.5);
        var rfSrc = voltageSource("src-rf", "RF", AmplitudeKind.QUADRATURE, 0.001);
        var b0Coil = new CircuitComponent.Coil(new ComponentId("coil-b0"), "B0 Coil", null, 0, 0);
        var rfCoil = new CircuitComponent.Coil(new ComponentId("coil-rf"), "RF Coil", null, 0, 0);
        var gnd = new CircuitComponent.Ground(new ComponentId("gnd"), "GND");

        var wires = List.of(
            wire("w-b0-out", b0Src.id(), "out", b0Coil.id(), "a"),
            wire("w-rf-out", rfSrc.id(), "out", rfCoil.id(), "a"),
            wire("w-b0-gnd", b0Coil.id(), "b", gnd.id(), "a"),
            wire("w-rf-gnd", rfCoil.id(), "b", gnd.id(), "a")
        );
        var circuit = new CircuitDocument(new ProjectNodeId("c-0"), "c",
            List.of(b0Src, rfSrc, b0Coil, rfCoil, gnd), wires, CircuitLayout.empty());
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
        var gnd = new CircuitComponent.Ground(new ComponentId("gnd"), "GND");

        var wires = List.of(
            wire("w1", rfSrc.id(), "out", sw.id(), "a"),
            wire("w2", sw.id(), "b", coil.id(), "a"),
            wire("w3", coil.id(), "b", gnd.id(), "a"),
            wire("w4", gate.id(), "out", sw.id(), "ctl")
        );
        var circuit = new CircuitDocument(new ProjectNodeId("c-1"), "c",
            List.of(rfSrc, gate, sw, coil, gnd), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(circuit, ProjectRepository.untitled(), R, Z);

        assertEquals(1, compiled.drives().size(), "RF source drives exactly one coil");
        var rfLink = compiled.drives().get(0);
        assertEquals(1, rfLink.switchIndices().size(), "Switch on path");
        assertEquals(0, rfLink.switchIndices().get(0));

        var compiledSw = compiled.switches().get(0);
        assertEquals(1, compiledSw.ctlSourceIndex(), "Switch ctl resolves to gate source");
    }

    @Test
    void floatingSwitchControl_resolvesAsPermanentlyOpen() {
        var src = voltageSource("src-rf", "RF", AmplitudeKind.REAL, 1);
        var sw = new CircuitComponent.SwitchComponent(new ComponentId("sw"), "Sw", 0.5, 1e9, 0.5);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "Coil", null, 0, 0);
        var gnd = new CircuitComponent.Ground(new ComponentId("gnd"), "GND");
        var wires = List.of(
            wire("w1", src.id(), "out", sw.id(), "a"),
            wire("w2", sw.id(), "b", coil.id(), "a"),
            wire("w3", coil.id(), "b", gnd.id(), "a")
        );
        var circuit = new CircuitDocument(new ProjectNodeId("c-2"), "c",
            List.of(src, sw, coil, gnd), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(circuit, ProjectRepository.untitled(), R, Z);
        assertEquals(-1, compiled.switches().get(0).ctlSourceIndex(),
            "Unwired ctl means the switch defaults to open");
    }

    @Test
    void coilWithoutGroundReturn_producesNoDriveLink() {
        // Source drives coil, but the coil's other terminal is floating (not wired
        // to ground). The compiler treats this as an incomplete link.
        var src = voltageSource("src", "S", AmplitudeKind.REAL, 1);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "Coil", null, 0, 0);
        var gnd = new CircuitComponent.Ground(new ComponentId("gnd"), "GND");
        var wires = List.of(
            wire("w1", src.id(), "out", coil.id(), "a")
            // no coil.b → gnd — invalid return path
        );
        var circuit = new CircuitDocument(new ProjectNodeId("c-3"), "c",
            List.of(src, coil, gnd), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(circuit, ProjectRepository.untitled(), R, Z);
        assertTrue(compiled.drives().isEmpty(),
            "Source whose coil return is floating has no valid link");
    }

    @Test
    void probeThroughSwitchResolvesAsObserveLink() {
        var rfSrc = voltageSource("src-rf", "RF", AmplitudeKind.QUADRATURE, 0.001);
        var gate = voltageSource("src-rxgate", "RX Gate", AmplitudeKind.GATE, 1);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "Coil", null, 0, 0);
        var sw = new CircuitComponent.SwitchComponent(new ComponentId("sw"), "Sw", 0.5, 1e9, 0.5);
        var probe = new CircuitComponent.Probe(new ComponentId("probe"), "RX", 1, 0, Double.POSITIVE_INFINITY);
        var gnd = new CircuitComponent.Ground(new ComponentId("gnd"), "GND");

        var wires = List.of(
            // RF drives coil directly.
            wire("w1", rfSrc.id(), "out", coil.id(), "a"),
            wire("w2", coil.id(), "b", gnd.id(), "a"),
            // Probe observes the coil through the switch.
            wire("w3", probe.id(), "in", sw.id(), "a"),
            wire("w4", sw.id(), "b", coil.id(), "a"),
            // Gate drives the switch.
            wire("w5", gate.id(), "out", sw.id(), "ctl")
        );
        var circuit = new CircuitDocument(new ProjectNodeId("c-4"), "c",
            List.of(rfSrc, gate, coil, sw, probe, gnd), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(circuit, ProjectRepository.untitled(), R, Z);

        assertEquals(1, compiled.observes().size(), "Probe produces one observe link");
        var observe = compiled.observes().get(0);
        assertEquals(0, observe.coilIndex());
        assertEquals(1, observe.switchIndices().size(),
            "Observe link carries the switch for gating");
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
