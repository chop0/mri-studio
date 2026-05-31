package ax.xz.mri.service.circuit.mna;
import ax.xz.mri.model.field.CylindricalGrid;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.Wire;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.service.circuit.CircuitCompiler;
import ax.xz.mri.service.circuit.CompiledCircuit;
import ax.xz.mri.support.EigenfieldScripts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Probes the noise floor of the receive chain — coil → T/R mux → mixer → probe —
 * with a synthetic EMF supplied directly in place of the reciprocity coupling
 * the Bloch simulator would compute. With a clean (constant or slowly varying)
 * EMF input, the probe voltage should track it without sample-to-sample
 * oscillation. Any noise that appears here is a bug in the MNA / mixer /
 * modulator pipeline, not in the Bloch simulator.
 */
class MnaProbeStabilityTest {

    private static final double[] R = {0};
    private static final double[] Z = {0};

    /**
     * Single coil, mux in RX mode, mixer with deltaOmega = 0. With a constant
     * EMF, every step's probe voltage should be identical.
     */
    @Test
    void constantEmfYieldsConstantProbeVoltage() {
        var rig = buildRxChain(/* loHz= */ 1e6, /* omegaSim= */ 2 * Math.PI * 1e6);
        var solver = new MnaSolver(rig.compiled.mna(), rig.compiled);
        var out = new MnaSolver.StepOut(rig.compiled.coils().size(), 1);

        double dt = 1e-6;
        double emf = 5.0;
        Double firstReal = null, firstImag = null;
        for (int step = 0; step < 50; step++) {
            solver.step(rig.zeroControls(), new double[]{emf}, new double[]{0.0}, dt, step * dt,
                2 * Math.PI * 1e6, out);
            if (firstReal == null) {
                firstReal = out.probeVReal()[0];
                firstImag = out.probeVImag()[0];
                continue;
            }
            assertEquals(firstReal, out.probeVReal()[0], 1e-12,
                "step " + step + " probe real diverged from step 0");
            assertEquals(firstImag, out.probeVImag()[0], 1e-12,
                "step " + step + " probe imag diverged from step 0");
        }
    }

    /**
     * EMF that varies smoothly across steps (linear ramp). The probe voltage
     * should also be a linear ramp — no high-frequency overlay from the
     * lab-frame conversion or the mixer/modulator iterations.
     */
    @Test
    void linearlyRampingEmfYieldsLinearProbeVoltage() {
        var rig = buildRxChain(/* loHz= */ 1e6, /* omegaSim= */ 2 * Math.PI * 1e6);
        var solver = new MnaSolver(rig.compiled.mna(), rig.compiled);
        var out = new MnaSolver.StepOut(rig.compiled.coils().size(), 1);

        double dt = 1e-6;
        double[] probeReal = new double[20];
        double[] probeImag = new double[20];
        for (int step = 0; step < 20; step++) {
            double emf = 1.0 + 0.1 * step;
            solver.step(rig.zeroControls(), new double[]{emf}, new double[]{0.0}, dt, step * dt,
                2 * Math.PI * 1e6, out);
            probeReal[step] = out.probeVReal()[0];
            probeImag[step] = out.probeVImag()[0];
        }

        // Second-difference of a linear ramp is zero. Check that the probe
        // voltage's second difference is below tolerance — that's the
        // signature of a curvature-free (i.e. linear) response.
        for (int step = 2; step < 20; step++) {
            double secondDiff = probeReal[step] - 2 * probeReal[step - 1] + probeReal[step - 2];
            assertEquals(0.0, secondDiff, 1e-12,
                "probe real second-difference at step " + step + " = " + secondDiff
                    + " (probe series should be linear in EMF input)");
        }
    }

    /**
     * Hand the receive chain a sinusoidal EMF whose period straddles many
     * sample steps. The probe magnitude should match the EMF magnitude at
     * every step — no aliasing, no oscillation overlay.
     */
    @Test
    void sinusoidalEmfYieldsMatchingProbeMagnitude() {
        var rig = buildRxChain(/* loHz= */ 1e6, /* omegaSim= */ 2 * Math.PI * 1e6);
        var solver = new MnaSolver(rig.compiled.mna(), rig.compiled);
        var out = new MnaSolver.StepOut(rig.compiled.coils().size(), 1);

        double dt = 1e-6;
        // EMF sinusoid at 10 kHz — period = 100 µs, sampled every 1 µs (100 samples per period).
        double emfFreqHz = 10e3;

        for (int step = 0; step < 200; step++) {
            double t = step * dt;
            double emfRe = Math.cos(2 * Math.PI * emfFreqHz * t);
            double emfIm = Math.sin(2 * Math.PI * emfFreqHz * t);
            solver.step(rig.zeroControls(), new double[]{emfRe}, new double[]{emfIm}, dt, t,
                2 * Math.PI * 1e6, out);
            if (step < 2) continue;  // skip initial-condition transient
            double probeMag = Math.hypot(out.probeVReal()[0], out.probeVImag()[0]);
            assertEquals(1.0, probeMag, 1e-6,
                "step " + step + ": probe magnitude " + probeMag + " ≠ |EMF| = 1. "
                    + "If the IQ mixer is dropping the imag channel, |probe| oscillates between 0 and 1 "
                    + "as the EMF rotates in the complex plane — that's the bug.");
        }
    }

    // ───────── Rig ─────────

    private record Rig(CompiledCircuit compiled, int controlChannelCount) {
        double[] zeroControls() { return new double[controlChannelCount]; }
    }

    /**
     * RF coil → T/R mux (held in RX mode) → I/Q mixer at {@code loHz} → probe.
     * No transmit source — a separate STATIC source on a tiny aux coil keeps the
     * MNA non-singular. The mux is held in RX mode by a metadata tap that reads
     * a deliberately-inactive REAL "RF gate" source.
     */
    private static Rig buildRxChain(double mixerLoHz, double omegaSim) {
        var repo = ax.xz.mri.state.ProjectState.empty();
        var efDoc = new EigenfieldDocument(new ProjectNodeId("ef"), "ef", "",
            EigenfieldScripts.wrap("return Vec3.of(1, 0, 0);"), "T");
        repo = repo.withEigenfield(efDoc);
        var efId = efDoc.id();

        // Inactive REAL source → metadata tap "RF active" → mux ctl. Stays at 0
        // because we never push a non-zero control value, so mux closes b↔common.
        var rfGate = new CircuitComponent.VoltageSource(new ComponentId("src-gate"), "RF gate",
            AmplitudeKind.REAL, 0, 0, 1.0, 0);
        var rfActive = new CircuitComponent.VoltageMetadata(new ComponentId("meta"),
            "RF active", "RF gate");
        var rfCoil = new CircuitComponent.Coil(new ComponentId("coil-rf"), "RF Coil",
            efId, 0, 1.0, 1.0);
        var trMux = new CircuitComponent.Multiplexer(new ComponentId("mux"), "T/R Mux",
            1e-6, 1e9, 0.5);
        var rxMixer = new CircuitComponent.Mixer(new ComponentId("dc"), "Demod", mixerLoHz);
        var probe = new CircuitComponent.Probe(new ComponentId("probe"), "RX",
            1.0, 0.0, Double.POSITIVE_INFINITY);

        var wires = List.of(
            wire("w-gate-meta-implicit", rfGate.id(), "out", trMux.id(), "a"),
            wire("w-mux-coil", trMux.id(), "common", rfCoil.id(), "in"),
            wire("w-mux-mixer", trMux.id(), "b", rxMixer.id(), "in"),
            wire("w-mixer-probe", rxMixer.id(), "out0", probe.id(), "in"),
            wire("w-meta-ctl", rfActive.id(), "out", trMux.id(), "ctl")
        );

        var doc = new CircuitDocument(new ProjectNodeId("c"), "RX-only",
            List.of(rfGate, rfActive, rfCoil, trMux, rxMixer, probe), wires, CircuitLayout.empty());
        var compiled = CircuitCompiler.compile(doc, repo, new CylindricalGrid(R, Z));
        return new Rig(compiled, 1);
    }

    private static Wire wire(String id, ComponentId a, String ap, ComponentId b, String bp) {
        return new Wire(id, new ComponentTerminal(a, ap), new ComponentTerminal(b, bp));
    }
}
