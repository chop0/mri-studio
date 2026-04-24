package ax.xz.mri.service.circuit.mna;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.Wire;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.service.circuit.CircuitCompiler;
import ax.xz.mri.service.circuit.CompiledCircuit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Numerical tests for {@link MnaSolver}. Each test builds a small
 * {@link CircuitDocument}, compiles it, steps the solver, and checks the
 * resulting coil currents and probe voltages against hand-computed values.
 */
class MnaSolverTest {

    private static final double[] R = {0};
    private static final double[] Z = {0};

    @Test
    void idealRealSourceToCoilProducesVoverRCurrent() {
        var compiled = compileSingleDriveCircuit(AmplitudeKind.REAL, /* coilR */ 2.0);
        var solver = new MnaSolver(compiled.mna(), compiled);
        var out = new MnaSolver.StepOut(1, 1);

        // REAL source occupies channel 0 with value 6 V. Switch is direct; no
        // impedance between source and coil apart from the coil's own 2 Ω.
        solver.step(new double[]{6.0}, null, null, 1e-6, 0, 0, out);
        assertEquals(3.0, out.coilIReal()[0], 1e-9);
        assertEquals(0.0, out.coilIImag()[0], 1e-9);
    }

    @Test
    void quadratureSourceContributesIAndQIndependently() {
        var compiled = compileSingleDriveCircuit(AmplitudeKind.QUADRATURE, /* coilR */ 1.0);
        var solver = new MnaSolver(compiled.mna(), compiled);
        var out = new MnaSolver.StepOut(1, 1);

        solver.step(new double[]{3.0, 4.0}, null, null, 1e-6, 0, 0, out);
        assertEquals(3.0, out.coilIReal()[0], 1e-9);
        assertEquals(4.0, out.coilIImag()[0], 1e-9);
    }

    @Test
    void staticSourceContributesItsFixedAmplitudeEveryStep() {
        var compiled = compileSingleDriveCircuit(AmplitudeKind.STATIC, /* coilR */ 1.0);
        var solver = new MnaSolver(compiled.mna(), compiled);
        var out = new MnaSolver.StepOut(1, 1);

        // STATIC source's "staticAmplitude" is pulled from maxAmplitude = 1.5.
        solver.step(new double[0], null, null, 1e-6, 0, 0, out);
        assertEquals(1.5, out.coilIReal()[0], 1e-9);
    }

    @Test
    void inductorChargingFollowsBackwardEuler() {
        // 1 V source, 2 Ω + 10 mH coil. With backward Euler:
        //   I[n] = I[n-1] + dt/L · (V - R·I[n-1]) / (1 + R·dt/L)   ← effectively
        // Solve analytically: steady state = 0.5 A; rise with time constant L/R = 5 ms.
        var compiled = compileRLCircuit(1.0, 2.0, 10e-3);
        var solver = new MnaSolver(compiled.mna(), compiled);
        var out = new MnaSolver.StepOut(1, 1);

        double dt = 1e-3;
        // Run 20 time constants.
        for (int i = 0; i < 100; i++) {
            solver.step(new double[]{1.0}, null, null, dt, i * dt, 0, out);
        }
        assertEquals(0.5, out.coilIReal()[0], 1e-3, "current approaches V/R at steady state");

        // A fresh solver right at t=0 should have zero history; first step
        // should give less than steady-state.
        solver.resetHistory();
        solver.step(new double[]{1.0}, null, null, dt, 0, 0, out);
        assertTrue(out.coilIReal()[0] < 0.5, "first step hasn't reached steady state");
        assertTrue(out.coilIReal()[0] > 0, "but current does rise in the direction of V");
    }

    @Test
    void muxRoutesCommonToWhicheverCtlBranchIsActive() {
        // RF source → mux.a; probe → mux.b; mux.common → coil.
        // A VoltageMetadata tap observes the RF source and drives mux.ctl
        // with the "active" flag — 1 when any RF control channel is non-zero,
        // 0 otherwise. With RF active the mux.a-common branch closes and the
        // coil current = RF voltage / small-R. With RF inactive the inverted
        // mux.b-common branch closes instead; the probe is high-Z so no
        // current flows through the coil.
        var repo = ProjectRepository.untitled();
        var efId = addEigenfield(repo, "ef");
        var rfSrc = voltageSource("src-rf", "RF", AmplitudeKind.REAL, 1.0);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "Coil", efId, 0, 0);
        var probe = new CircuitComponent.Probe(new ComponentId("probe"), "RX", 1.0, 0.0, Double.POSITIVE_INFINITY);
        var mux = new CircuitComponent.Multiplexer(new ComponentId("mux"), "TRmux",
            1e-6, 1e9, 0.5);
        // Metadata tap references the RF source by name.
        var rfActive = new CircuitComponent.VoltageMetadata(new ComponentId("meta"), "RF active", "RF");
        var wires = List.of(
            wire("w1", rfSrc.id(), "out", mux.id(), "a"),
            wire("w2", probe.id(), "in", mux.id(), "b"),
            wire("w3", mux.id(), "common", coil.id(), "in"),
            wire("w4", rfActive.id(), "out", mux.id(), "ctl")
        );
        var doc = new CircuitDocument(new ProjectNodeId("c"), "c",
            List.of(rfSrc, rfActive, coil, probe, mux), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(doc, repo, R, Z);
        var solver = new MnaSolver(compiled.mna(), compiled);
        var out = new MnaSolver.StepOut(1, 1);

        // TX phase: source driving.
        solver.step(new double[]{2.0}, null, null, 1e-6, 0, 0, out);
        assertEquals(2.0, out.coilIReal()[0], 1e-3, "TX: source drives coil through closed mux.a");

        // RX phase: no source drive. Probe is now coupled to coil; with no EMF,
        // nothing drives the net so coil current is 0.
        solver.resetHistory();
        solver.step(new double[]{0.0}, null, null, 1e-6, 0, 0, out);
        assertEquals(0.0, out.coilIReal()[0], 1e-9, "RX: no source, no coil current");
    }

    @Test
    void mixerBuffersItsInputAndFrameShiftsToOutput() {
        // source (V=2) → coil (so the in-node has a well-defined voltage)
        // coil.in → mixer.in
        // mixer.out → shunt-to-ground load
        // probe → mixer.out (observer)
        // With loHz = 0, the mixer should be a unity buffer: V(out) = V(in)
        // every step. The load on V(out) must NOT change V(in) (buffered).
        var repo = ProjectRepository.untitled();
        var efId = addEigenfield(repo, "ef");
        var src = voltageSource("src", "V", AmplitudeKind.REAL, 2.0);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "Coil", efId, 0, 0);
        var mixer = new CircuitComponent.Mixer(new ComponentId("mx"), "Mix", 0);
        var load = new CircuitComponent.ShuntResistor(new ComponentId("load"), "Rp", 10);
        var probe = new CircuitComponent.Probe(new ComponentId("probe"), "RX",
            1.0, 0.0, Double.POSITIVE_INFINITY);
        var wires = List.of(
            wire("w1", src.id(), "out", coil.id(), "in"),
            wire("w2", coil.id(), "in", mixer.id(), "in"),
            wire("w3", mixer.id(), "out", load.id(), "in"),
            wire("w4", mixer.id(), "out", probe.id(), "in")
        );
        var doc = new CircuitDocument(new ProjectNodeId("c"), "c",
            List.of(src, coil, mixer, load, probe), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(doc, repo, R, Z);
        var solver = new MnaSolver(compiled.mna(), compiled);
        var out = new MnaSolver.StepOut(1, 1);

        solver.step(new double[]{2.0}, null, null, 1e-6, 0, 0, out);
        // Mixer output equals input (loHz=0 ⇒ unity buffer), so probe reads V=2.
        assertEquals(2.0, out.probeVReal()[0], 1e-6,
            "unity-LO mixer should pass its input straight through");
        assertEquals(0.0, out.probeVImag()[0], 1e-6);
        // The coil current must still be V/R = 2/1 = 2 A regardless of the
        // load on the mixer's output — this is the "buffered" property.
        assertEquals(2.0, out.coilIReal()[0], 1e-6,
            "load on mixer.out must not draw current through mixer.in");
    }

    @Test
    void mixerRotationShiftsOutputFrameByNegativeLo() {
        // Mixer with loHz = 1 MHz applies exp(-j·2π·1e6·t). Over a fixed dt
        // after a single step, the output phase should be −2π·1e6·t relative
        // to the input (which is a constant 1 V from a REAL source).
        var repo = ProjectRepository.untitled();
        var efId = addEigenfield(repo, "ef");
        var src = voltageSource("src", "V", AmplitudeKind.REAL, 1.0);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "Coil", efId, 0, 0);
        var mixer = new CircuitComponent.Mixer(new ComponentId("mx"), "Mix", 1_000_000);
        var probe = new CircuitComponent.Probe(new ComponentId("probe"), "RX",
            1.0, 0.0, Double.POSITIVE_INFINITY);
        var wires = List.of(
            wire("w1", src.id(), "out", coil.id(), "in"),
            wire("w2", coil.id(), "in", mixer.id(), "in"),
            wire("w3", mixer.id(), "out", probe.id(), "in")
        );
        var doc = new CircuitDocument(new ProjectNodeId("c"), "c",
            List.of(src, coil, mixer, probe), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(doc, repo, R, Z);
        var solver = new MnaSolver(compiled.mna(), compiled);
        var out = new MnaSolver.StepOut(1, 1);

        double dt = 1e-6;
        double tSeconds = 2 * dt;     // pick a moment where the rotation is non-zero.
        solver.step(new double[]{1.0}, null, null, dt, tSeconds, 0, out);
        double theta = 2 * Math.PI * 1_000_000 * tSeconds;
        assertEquals(Math.cos(theta), out.probeVReal()[0], 1e-6);
        assertEquals(-Math.sin(theta), out.probeVImag()[0], 1e-6);
    }

    // ───────── Helpers ─────────

    private static CompiledCircuit compileSingleDriveCircuit(AmplitudeKind kind, double coilR) {
        var repo = ProjectRepository.untitled();
        var efId = addEigenfield(repo, "ef");
        var src = voltageSource("src", "S", kind, 1.5);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "Coil", efId, 0, coilR);
        var wires = List.of(wire("w", src.id(), "out", coil.id(), "in"));
        var doc = new CircuitDocument(new ProjectNodeId("c"), "c",
            List.of(src, coil), wires, CircuitLayout.empty());
        return CircuitCompiler.compile(doc, repo, R, Z);
    }

    private static CompiledCircuit compileRLCircuit(double v, double r, double l) {
        var repo = ProjectRepository.untitled();
        var efId = addEigenfield(repo, "ef");
        var src = voltageSource("src", "V", AmplitudeKind.REAL, v);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "Coil", efId, l, r);
        var wires = List.of(wire("w", src.id(), "out", coil.id(), "in"));
        var doc = new CircuitDocument(new ProjectNodeId("c"), "c",
            List.of(src, coil), wires, CircuitLayout.empty());
        return CircuitCompiler.compile(doc, repo, R, Z);
    }

    private static CircuitComponent.VoltageSource voltageSource(
        String id, String name, AmplitudeKind kind, double amp
    ) {
        return new CircuitComponent.VoltageSource(new ComponentId(id), name, kind, 0, 0, amp, 0);
    }

    private static Wire wire(String id, ComponentId a, String ap, ComponentId b, String bp) {
        return new Wire(id, new ComponentTerminal(a, ap), new ComponentTerminal(b, bp));
    }

    private static ProjectNodeId addEigenfield(ProjectRepository repo, String id) {
        var nodeId = new ProjectNodeId(id);
        repo.addEigenfield(new EigenfieldDocument(nodeId, id, "",
            "return Vec3.of(1, 0, 0);", "T", 1.0));
        return nodeId;
    }
}
