package ax.xz.mri.service.circuit;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.Wire;
import ax.xz.mri.model.circuit.compile.CtlBinding;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.service.circuit.mna.MnaNetwork;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiles a CircuitDocument and checks that the resulting MnaNetwork
 * carries the expected stamps. The actual numerical solve is exercised in
 * MnaSolverTest.
 */
class CircuitCompilerTest {

    private static final double[] R = {0, 10};
    private static final double[] Z = {-10, 10};

    @Test
    void simpleSourceDrivingCoilProducesOneOutBranchAndOneCoilBranch() {
        var src = voltageSource("src", "RF", AmplitudeKind.REAL, 1);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "Coil", null, 0, 0);
        var wires = List.of(
            wire("w", src.id(), "out", coil.id(), "in")
        );
        var circuit = new CircuitDocument(new ProjectNodeId("c"), "c",
            List.of(src, coil), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(circuit, ProjectRepository.untitled(), R, Z);

        var mna = compiled.mna();
        // One source-out branch + one coil branch = 2 total. The source's
        // "active" flag lives on a separate VoltageMetadata block nowadays,
        // so a bare source no longer carries a second branch.
        assertEquals(2, mna.branchCount());
        assertEquals(0, mna.sourceOutBranch()[0]);
        assertEquals(1, mna.coilBranch()[0]);
        // Default series resistance kicks in for R=0 L=0 coils.
        assertEquals(1.0, mna.branchR()[mna.coilBranch()[0]], 1e-12);
    }

    @Test
    void switchCtlWiredToSourceOutBindsToFromSourceOut() {
        var rfSrc = voltageSource("src-rf", "RF", AmplitudeKind.QUADRATURE, 0.001);
        var gate = voltageSource("src-gate", "Gate", AmplitudeKind.GATE, 1);
        var sw = new CircuitComponent.SwitchComponent(new ComponentId("sw"), "Sw", 0.5, 1e9, 0.5);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "Coil", null, 0, 0);
        var wires = List.of(
            wire("w1", rfSrc.id(), "out", sw.id(), "a"),
            wire("w2", sw.id(), "b", coil.id(), "in"),
            wire("w3", gate.id(), "out", sw.id(), "ctl")
        );
        var circuit = new CircuitDocument(new ProjectNodeId("c"), "c",
            List.of(rfSrc, gate, sw, coil), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(circuit, ProjectRepository.untitled(), R, Z);

        assertEquals(1, compiled.mna().switchCount());
        var ctl = compiled.mna().switchCtl()[0];
        assertInstanceOf(CtlBinding.FromSourceOut.class, ctl);
    }

    @Test
    void switchCtlWiredToVoltageMetadataBindsToFromSourceActive() {
        var rfSrc = voltageSource("src-rf", "RF", AmplitudeKind.QUADRATURE, 0.001);
        // Metadata tap references the source by name (not by wire).
        var meta = new CircuitComponent.VoltageMetadata(new ComponentId("meta"), "RF active", "RF");
        var sw = new CircuitComponent.SwitchComponent(new ComponentId("sw"), "Sw", 0.5, 1e9, 0.5);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "Coil", null, 0, 0);
        var wires = List.of(
            wire("w1", rfSrc.id(), "out", sw.id(), "a"),
            wire("w2", sw.id(), "b", coil.id(), "in"),
            wire("w3", meta.id(), "out", sw.id(), "ctl")
        );
        var circuit = new CircuitDocument(new ProjectNodeId("c"), "c",
            List.of(rfSrc, meta, sw, coil), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(circuit, ProjectRepository.untitled(), R, Z);

        var ctl = compiled.mna().switchCtl()[0];
        assertInstanceOf(CtlBinding.FromSourceActive.class, ctl);
    }

    @Test
    void floatingSwitchControlResolvesAsAlwaysOpen() {
        var src = voltageSource("src", "RF", AmplitudeKind.REAL, 1);
        var sw = new CircuitComponent.SwitchComponent(new ComponentId("sw"), "Sw", 0.5, 1e9, 0.5);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "Coil", null, 0, 0);
        var wires = List.of(
            wire("w1", src.id(), "out", sw.id(), "a"),
            wire("w2", sw.id(), "b", coil.id(), "in")
        );
        var circuit = new CircuitDocument(new ProjectNodeId("c"), "c",
            List.of(src, sw, coil), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(circuit, ProjectRepository.untitled(), R, Z);

        var ctl = compiled.mna().switchCtl()[0];
        assertInstanceOf(CtlBinding.AlwaysOpen.class, ctl);
    }

    @Test
    void multiplexerExpandsIntoTwoOppositePolaritySwitchStamps() {
        var rfSrc = voltageSource("src-rf", "RF", AmplitudeKind.QUADRATURE, 0.001);
        var meta = new CircuitComponent.VoltageMetadata(new ComponentId("meta"), "RF active", "RF");
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "Coil", null, 0, 0);
        var probe = new CircuitComponent.Probe(new ComponentId("probe"), "RX", 1, 0, Double.POSITIVE_INFINITY);
        var mux = new CircuitComponent.Multiplexer(new ComponentId("mux"), "TRmux", 0.5, 1e9, 0.5);
        var wires = List.of(
            wire("w1", rfSrc.id(), "out", mux.id(), "a"),
            wire("w2", probe.id(), "in", mux.id(), "b"),
            wire("w3", mux.id(), "common", coil.id(), "in"),
            wire("w4", meta.id(), "out", mux.id(), "ctl")
        );
        var circuit = new CircuitDocument(new ProjectNodeId("c"), "c",
            List.of(rfSrc, meta, coil, probe, mux), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(circuit, ProjectRepository.untitled(), R, Z);

        // One mux → two switch stamps.
        assertEquals(2, compiled.mna().switchCount());
        assertFalse(compiled.mna().switchInvert()[0]);
        assertTrue(compiled.mna().switchInvert()[1]);
        // Both share the same ctl binding.
        var ctl0 = compiled.mna().switchCtl()[0];
        var ctl1 = compiled.mna().switchCtl()[1];
        assertInstanceOf(CtlBinding.FromSourceActive.class, ctl0);
        assertEquals(ctl0, ctl1);
    }

    @Test
    void shuntResistorStampsOneResistorToGround() {
        var src = voltageSource("src", "V", AmplitudeKind.REAL, 1);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "C", null, 0, 0);
        var rShunt = new CircuitComponent.ShuntResistor(new ComponentId("rshunt"), "Rp", 100);
        var wires = List.of(
            wire("w1", src.id(), "out", coil.id(), "in"),
            wire("w2", rShunt.id(), "in", coil.id(), "in")
        );
        var circuit = new CircuitDocument(new ProjectNodeId("c"), "c",
            List.of(src, coil, rShunt), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(circuit, ProjectRepository.untitled(), R, Z);

        assertEquals(1, compiled.mna().resistorCount());
        assertEquals(-1, compiled.mna().resistorB()[0], "shunt returns to ground");
        assertEquals(1.0 / 100, compiled.mna().resistorConductance()[0], 1e-12);
    }

    @Test
    void passiveInductorGetsItsOwnBranch() {
        var src = voltageSource("src", "V", AmplitudeKind.REAL, 1);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "C", null, 0, 0);
        var l = new CircuitComponent.Inductor(new ComponentId("l"), "L", 1e-6);
        var wires = List.of(
            wire("w1", src.id(), "out", l.id(), "a"),
            wire("w2", l.id(), "b", coil.id(), "in")
        );
        var circuit = new CircuitDocument(new ProjectNodeId("c"), "c",
            List.of(src, coil, l), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(circuit, ProjectRepository.untitled(), R, Z);

        boolean foundPassive = false;
        for (int b = 0; b < compiled.mna().branchCount(); b++) {
            if (compiled.mna().branchKind()[b] == MnaNetwork.VBranchKind.PASSIVE_INDUCTOR) {
                foundPassive = true;
                assertEquals(1e-6, compiled.mna().branchL()[b], 1e-15);
                assertEquals(0.0, compiled.mna().branchR()[b], 1e-15);
            }
        }
        assertTrue(foundPassive, "passive inductor should produce a PASSIVE_INDUCTOR branch");
    }

    @Test
    void mixerStampsABranchAndExposesItsInAndLoHz() {
        // The mixer is an electrical element — a buffered VCVS with a
        // time-varying complex gain. The compiler should place a MIXER_OUT
        // branch on its out-port and register its in-node + loHz for the
        // solver to resolve each step.
        var src = voltageSource("src", "V", AmplitudeKind.REAL, 1);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "C", null, 0, 0);
        var mixer = new CircuitComponent.Mixer(new ComponentId("mx"), "Mix", 1_234);
        var probe = new CircuitComponent.Probe(new ComponentId("probe"), "RX",
            1.0, 0.0, Double.POSITIVE_INFINITY);
        var wires = List.of(
            wire("w1", src.id(), "out", coil.id(), "in"),
            wire("w2", coil.id(), "in", mixer.id(), "in"),
            wire("w3", mixer.id(), "out", probe.id(), "in")
        );
        var circuit = new CircuitDocument(new ProjectNodeId("c"), "c",
            List.of(src, coil, mixer, probe), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(circuit, ProjectRepository.untitled(), R, Z);
        var mna = compiled.mna();

        assertEquals(1, mna.mixerCount());
        assertEquals(1_234, mna.mixerLoHz()[0], 1e-12);
        int b = mna.mixerOutBranch()[0];
        assertEquals(MnaNetwork.VBranchKind.MIXER_OUT, mna.branchKind()[b]);
        // Mixer's in-node and the coil's node must be the same — they are
        // wired together, and no virtual-alias trickery intervenes.
        int coilNode = mna.branchNodeA()[mna.coilBranch()[0]];
        assertEquals(coilNode, mna.mixerInNode()[0]);
        // The probe's node and the mixer-out node are the same — they're
        // wired together too.
        assertEquals(mna.branchNodeA()[b], mna.probeNode()[0]);
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
