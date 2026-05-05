package ax.xz.mri.service.simulation;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.SimulationOutputFactory;
import ax.xz.mri.ui.wizard.starters.SimConfigTemplate;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.model.simulation.PhysicsParams;
import ax.xz.mri.ui.viewmodel.ProjectSessionViewModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for a CPMG (Carr–Purcell–Meiboom–Gill) T2
 * measurement built on the low-field MRI template.
 *
 * <p>Regression guard for the circuit-level rewrite. A previously reported bug
 * ("y-flip pulses don't do anything") is caught by
 * {@link #ninetyXPulseMovesMagnetisationIntoTransversePlane}; that test failed
 * because the circuit's voltage-source order made {@code PulseStep.controls[0..1]}
 * map to gradients, not RF.
 *
 * <p>Simulator sign convention: {@code dM/dt = −γ M × B} (effective, after
 * frame choice). A 90°x pulse on thermal equilibrium produces {@code (0, −1, 0)}.
 * A 180°y pulse reflects {@code (Mx, My, Mz) → (−Mx, My, −Mz)}.
 */
class CpmgIntegrationTest {

    private static final double DT = 1e-6;          // 1 µs
    private static final double B1_MAX = 200e-6;    // 200 µT
    private static final double GAMMA = 267.522e6;  // rad/s/T
    private static final double TAU_S = 1e-3;       // 1 ms pulse-to-pulse half-spacing

    private static int steps90() {
        double rabiRate = GAMMA * B1_MAX;
        return (int) Math.round((Math.PI / 2) / (rabiRate * DT));
    }

    private static int steps180() { return 2 * steps90(); }

    private static int stepsTau() { return (int) Math.round(TAU_S / DT); }

    private static Train buildCpmg(int nEchoes) {
        int n90 = steps90();
        int n180 = steps180();
        int nTau = stepsTau();

        var segments = new ArrayList<Segment>();
        var pulse = new ArrayList<PulseSegment>();

        // Channel layout from the low-field template: [rf_I, rf_Q, gx, gz].
        segments.add(new Segment(DT, 0, n90));
        pulse.add(filled(n90, new double[]{B1_MAX, 0, 0, 0}, 1.0));

        segments.add(new Segment(DT, nTau, 0));
        pulse.add(filled(nTau, new double[]{0, 0, 0, 0}, 0.0));

        for (int e = 0; e < nEchoes; e++) {
            segments.add(new Segment(DT, 0, n180));
            pulse.add(filled(n180, new double[]{0, B1_MAX, 0, 0}, 1.0));
            segments.add(new Segment(DT, 2 * nTau, 0));
            pulse.add(filled(2 * nTau, new double[]{0, 0, 0, 0}, 0.0));
        }
        return new Train(segments, pulse);
    }

    private static PulseSegment filled(int count, double[] controls, double gate) {
        var steps = new ArrayList<PulseStep>(count);
        for (int i = 0; i < count; i++) steps.add(new PulseStep(controls.clone(), gate));
        return new PulseSegment(steps);
    }

    private record Train(List<Segment> segments, List<PulseSegment> pulse) {}

    private static int[] segmentStepBoundaries(List<Segment> segments) {
        int[] boundaries = new int[segments.size() + 1];
        int acc = 0;
        for (int i = 0; i < segments.size(); i++) {
            boundaries[i] = acc;
            acc += segments.get(i).totalSteps();
        }
        boundaries[segments.size()] = acc;
        return boundaries;
    }

    /**
     * Swap the B0 coil's eigenfield for one with a deliberate linear z-ramp on
     * top of unity, so an isochromat at a known offset experiences predictable
     * off-resonance.
     */
    private static ax.xz.mri.state.ProjectState installZAxisOffResonance(
            ax.xz.mri.state.ProjectState state, SimulationConfig config, String suffix, double dBzPerMetre) {
        var circuit = state.circuit(config.circuitId());
        var b0Source = circuit.voltageSources().stream()
            .filter(s -> s.kind() == AmplitudeKind.STATIC)
            .findFirst().orElseThrow();
        double b0Amplitude = b0Source.maxAmplitude();
        double normalisedSlope = dBzPerMetre / b0Amplitude;
        String script = String.format("return Vec3.of(0, 0, 1 + %s * z);", normalisedSlope);
        var eigen = new EigenfieldDocument(
            new ProjectNodeId("ef-test-" + suffix), "B0 linear " + suffix, "test off-resonance",
            script, "T");

        var b0Coil = circuit.coils().stream()
            .filter(c -> c.name().equals("B0 Coil"))
            .findFirst().orElseThrow();
        var updated = circuit.replaceComponent(b0Coil.withEigenfieldId(eigen.id()));
        return state.withEigenfield(eigen).withCircuit(updated);
    }

    @Test
    void lowFieldTemplateExposesCanonicalChannelLayout() {
        var session = ProjectSessionViewModel.standalone();
        var doc = session.createSimConfig("CPMG-check",
            SimConfigTemplate.LOW_FIELD_MRI,
            PhysicsParams.DEFAULTS);
        var config = doc.config();
        var repo = session.project();
        CircuitDocument circuit = repo.circuit(config.circuitId());

        var b0 = circuit.voltageSources().stream().filter(s -> s.name().equals("B0")).findFirst().orElseThrow();
        var rfI = circuit.voltageSources().stream().filter(s -> s.name().equals("RF I")).findFirst().orElseThrow();
        var rfQ = circuit.voltageSources().stream().filter(s -> s.name().equals("RF Q")).findFirst().orElseThrow();
        var gx = circuit.voltageSources().stream().filter(s -> s.name().equals("Gradient X")).findFirst().orElseThrow();
        var gz = circuit.voltageSources().stream().filter(s -> s.name().equals("Gradient Z")).findFirst().orElseThrow();

        assertEquals(AmplitudeKind.STATIC, b0.kind());
        assertEquals(AmplitudeKind.REAL, rfI.kind());
        assertEquals(AmplitudeKind.REAL, rfQ.kind());
        assertEquals(AmplitudeKind.REAL, gx.kind());
        assertEquals(AmplitudeKind.REAL, gz.kind());

        // Channel layout: STATIC B0 contributes 0 channels; dynamic order is [RF I, RF Q, Gx, Gz].
        // The Modulator block composes RF I + RF Q into the Larmor drive.
        var dynamic = circuit.voltageSources().stream()
            .filter(s -> s.kind() != AmplitudeKind.STATIC).toList();
        assertEquals(4, dynamic.size(), "four dynamic sources: RF I, RF Q, Gx, Gz");
        assertEquals("RF I", dynamic.get(0).name());
        assertEquals("RF Q", dynamic.get(1).name());
        assertEquals("Gradient X", dynamic.get(2).name());
        assertEquals("Gradient Z", dynamic.get(3).name());

        // The Modulator block holds the Larmor carrier now.
        double expectedLarmor = GAMMA * 0.0154 / (2 * Math.PI);
        var modulator = circuit.components().stream()
            .filter(c -> c instanceof ax.xz.mri.model.circuit.CircuitComponent.Modulator)
            .map(c -> (ax.xz.mri.model.circuit.CircuitComponent.Modulator) c)
            .findFirst().orElseThrow();
        assertEquals(expectedLarmor, modulator.loHz(), 1.0);
        assertEquals(0.0154, config.referenceB0Tesla(), 1e-9);
    }

    @Test
    void ninetyXPulseMovesMagnetisationIntoTransversePlane() {
        BlochSimulator.clearCachesForTests();
        var session = ProjectSessionViewModel.standalone();
        var doc = session.createSimConfig("CPMG-90",
            SimConfigTemplate.LOW_FIELD_MRI,
            PhysicsParams.DEFAULTS);
        var config = doc.config();
        var repo = session.project();

        var train = buildCpmg(0);
        var data = SimulationOutputFactory.build(config, train.segments(), repo);

        // On-resonance at the isocentre: no dephasing during the 30 µs pulse.
        var trajectory = BlochSimulator.simulate(data, 0.0, 0.0, train.pulse());
        assertNotNull(trajectory);

        int afterExcitation = segmentStepBoundaries(train.segments())[1];
        double mx = trajectory.mxAt(afterExcitation);
        double my = trajectory.myAt(afterExcitation);
        double mz = trajectory.mzAt(afterExcitation);
        double mPerp = Math.hypot(mx, my);

        assertEquals(0.0, mz, 0.02, "Mz should be ~0 after a 90° pulse, got " + mz);
        assertEquals(1.0, mPerp, 0.02,
            "Transverse magnitude should be ~1 after a 90° pulse. " +
            "Got |M⊥| = " + mPerp + " (Mx=" + mx + ", My=" + my + "). " +
            "If |M⊥| ≈ 0 the RF is landing on the wrong channel.");

        // Sign convention: simulator integrates dM/dt = −γ M × B.
        // 90°x on thermal equilibrium → (0, −1, 0).
        assertEquals(-1.0, my, 0.05, "With the simulator's sign convention, My should be ~−1 after 90°x");
    }

    @Test
    void oneHundredEightyYPulseInvertsMxWhileKeepingMy() {
        BlochSimulator.clearCachesForTests();
        var session = ProjectSessionViewModel.standalone();
        var doc = session.createSimConfig("CPMG-180Y",
            SimConfigTemplate.LOW_FIELD_MRI,
            PhysicsParams.DEFAULTS);
        var config = doc.config();
        var repo = session.project();
        repo = installZAxisOffResonance(repo, config, "y-flip", 50e-6);

        var train = buildCpmg(1);
        var data = SimulationOutputFactory.build(config, train.segments(), repo);

        // 10 mm off isocentre → ~134 µrad/µs off-resonance → 0.13 rad in 1 ms.
        var trajectory = BlochSimulator.simulate(data, 0.0, 10.0, train.pulse());
        assertNotNull(trajectory);

        int[] b = segmentStepBoundaries(train.segments());
        double mxBefore = trajectory.mxAt(b[2]);
        double myBefore = trajectory.myAt(b[2]);
        double mxAfter = trajectory.mxAt(b[3]);
        double myAfter = trajectory.myAt(b[3]);

        assertTrue(Math.abs(mxBefore) > 0.05,
            "Off-resonance produced negligible dephasing (Mx=" + mxBefore + "). " +
            "Check that the eigenfield's gradient is actually being applied.");

        assertEquals(-mxBefore, mxAfter, 0.05,
            "180°y should flip Mx. Before=" + mxBefore + ", after=" + mxAfter);
        assertEquals(myBefore, myAfter, 0.05,
            "180°y should preserve My (rotation axis). Before=" + myBefore + ", after=" + myAfter);
    }

    @Test
    void cpmgEchoRefocusesDephasedEnsemble() {
        BlochSimulator.clearCachesForTests();
        var session = ProjectSessionViewModel.standalone();
        var doc = session.createSimConfig("CPMG-echo",
            SimConfigTemplate.LOW_FIELD_MRI,
            PhysicsParams.DEFAULTS);
        var config = doc.config();
        var repo = session.project();
        repo = installZAxisOffResonance(repo, config, "echo", 2e-3);

        var train = buildCpmg(1);
        var data = SimulationOutputFactory.build(config, train.segments(), repo);

        double[] zSamples = {-10, -5, 0, 5, 10};
        var trajectories = new ArrayList<ax.xz.mri.model.simulation.Trajectory>();
        for (double z : zSamples) {
            var traj = BlochSimulator.simulate(data, 0.0, z, train.pulse());
            assertNotNull(traj);
            trajectories.add(traj);
        }

        int[] b = segmentStepBoundaries(train.segments());

        double ensembleAfterExcite = coherentMperp(trajectories, b[1]);
        double ensembleAfterFree = coherentMperp(trajectories, b[2]);

        double peakEnsemble = 0;
        double peakTimeUs = 0;
        for (int i = b[3]; i < b[4]; i++) {
            double m = coherentMperp(trajectories, i);
            if (m > peakEnsemble) {
                peakEnsemble = m;
                peakTimeUs = trajectories.get(0).tAt(i);
            }
        }

        assertEquals(1.0, ensembleAfterExcite, 0.02,
            "Right after excitation, all spins are in phase — coherent |M⊥| ≈ 1");
        assertTrue(ensembleAfterFree < 0.4,
            "After τ of free precession the ensemble should dephase to near-zero. Got " + ensembleAfterFree);
        assertTrue(peakEnsemble > 0.95,
            "Ensemble echo peak should be close to 1 after refocusing. Got " + peakEnsemble);
        assertTrue(peakEnsemble > ensembleAfterFree + 0.1,
            "Echo peak should be clearly larger than the dephased signal. " +
            "Peak=" + peakEnsemble + ", pre-180 dephased=" + ensembleAfterFree);

        double winStart = trajectories.get(0).tAt(b[3]);
        double winEnd = trajectories.get(0).tAt(b[4] - 1);
        double margin = (winEnd - winStart) * 0.1;
        assertTrue(peakTimeUs > winStart + margin && peakTimeUs < winEnd - margin,
            "Ensemble echo peak should be inside the 2τ window, not at an edge. Got t=" + peakTimeUs);
    }

    private static double coherentMperp(List<ax.xz.mri.model.simulation.Trajectory> trajectories, int stepIdx) {
        double sx = 0, sy = 0;
        for (var t : trajectories) {
            sx += t.mxAt(stepIdx);
            sy += t.myAt(stepIdx);
        }
        return Math.hypot(sx, sy) / trajectories.size();
    }

    @Test
    void multiEchoCpmgPreservesSignalAcrossManyEchoes() {
        BlochSimulator.clearCachesForTests();
        var session = ProjectSessionViewModel.standalone();
        var doc = session.createSimConfig("CPMG-multi",
            SimConfigTemplate.LOW_FIELD_MRI,
            PhysicsParams.DEFAULTS);
        var config = doc.config();
        var repo = session.project();
        repo = installZAxisOffResonance(repo, config, "multi", 200e-6);

        int nEchoes = 4;
        var train = buildCpmg(nEchoes);
        var data = SimulationOutputFactory.build(config, train.segments(), repo);
        var trajectory = BlochSimulator.simulate(data, 0.0, 10.0, train.pulse());
        assertNotNull(trajectory);

        int[] b = segmentStepBoundaries(train.segments());
        double pulse90CentreUs = b[1] * 0.5;
        double[] echoTimes = new double[nEchoes];
        for (int e = 0; e < nEchoes; e++) {
            int refocusSegment = 2 + 2 * e;
            double pulse180CentreUs = (b[refocusSegment] + 0.5 * (b[refocusSegment + 1] - b[refocusSegment])) * 1.0;
            double tauUs = pulse180CentreUs - pulse90CentreUs;
            echoTimes[e] = pulse180CentreUs + tauUs;
            pulse90CentreUs = pulse180CentreUs;
        }

        for (int e = 0; e < nEchoes; e++) {
            double peak = 0;
            for (double t = echoTimes[e] - 200; t <= echoTimes[e] + 200; t += 5) {
                var state = trajectory.interpolateAt(t);
                if (state == null) continue;
                double m = Math.hypot(state.mx(), state.my());
                if (m > peak) peak = m;
            }
            assertTrue(peak > 0.90,
                "Echo #" + e + " peak |M⊥| = " + peak + " at t≈" + echoTimes[e] + " µs — should be > 0.9 (T2 is 100 ms)");
        }
    }

    @Test
    void longCpmgDoesNotCrashOrConsumeUnboundedMemory() {
        BlochSimulator.clearCachesForTests();
        var session = ProjectSessionViewModel.standalone();
        var doc = session.createSimConfig("CPMG-long",
            SimConfigTemplate.LOW_FIELD_MRI,
            PhysicsParams.DEFAULTS);
        var config = doc.config();
        var repo = session.project();

        int nEchoes = 40;
        var train = buildCpmg(nEchoes);
        var data = SimulationOutputFactory.build(config, train.segments(), repo);

        for (var p : new double[][]{{0, 0}, {5, 5}, {0, 10}, {15, -5}}) {
            var trajectory = BlochSimulator.simulate(data, p[0], p[1], train.pulse());
            assertNotNull(trajectory, "Simulation returned null for " + p[0] + ", " + p[1]);
            int last = trajectory.pointCount() - 1;
            double mag = Math.sqrt(
                trajectory.mxAt(last) * trajectory.mxAt(last)
              + trajectory.myAt(last) * trajectory.myAt(last)
              + trajectory.mzAt(last) * trajectory.mzAt(last));
            assertTrue(mag <= 1.05, "Magnetisation must stay bounded, got " + mag);
        }
    }
}
