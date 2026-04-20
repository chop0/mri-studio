package ax.xz.mri.service.simulation;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.BlochDataFactory;
import ax.xz.mri.model.simulation.FieldDefinition;
import ax.xz.mri.model.simulation.SimConfigTemplate;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.service.ObjectFactory;
import ax.xz.mri.ui.viewmodel.ProjectSessionViewModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for a CPMG (Carr–Purcell–Meiboom–Gill) T2
 * measurement built on the low-field MRI template.
 *
 * <p>This is a regression guard for the field-model rewrite. A previously
 * reported bug ("y-flip pulses don't do anything") is caught by
 * {@link #ninetyXPulseMovesMagnetisationIntoTransversePlane}; that test failed
 * because the sim-config template was emitting fields in an order that made
 * {@code PulseStep.controls[0..1]} map to gradients, not RF, so the RF
 * amplitudes landed on the wrong channels.
 *
 * <p>Simulator sign convention: {@code dM/dt = −γ M × B} (effective, after
 * frame choice). A 90°x pulse on thermal equilibrium produces {@code (0, −1, 0)}.
 * A 180°y pulse reflects {@code (Mx, My, Mz) → (−Mx, My, −Mz)}. Tests assert
 * either signed values (where the convention matters) or transverse magnitude
 * only (where it doesn't).
 */
class CpmgIntegrationTest {

    private static final double DT = 1e-6;          // 1 µs
    private static final double B1_MAX = 200e-6;    // 200 µT
    private static final double GAMMA = 267.522e6;  // rad/s/T
    private static final double TAU_S = 1e-3;       // 1 ms pulse-to-pulse half-spacing

    private static int steps90() {
        double rabiRate = GAMMA * B1_MAX;  // ~53 504 rad/s
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

        // Channel layout after the template rewrite: [rf_I, rf_Q, gx, gz].
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
     * Build a modified low-field config whose B0 eigenfield carries a deliberate
     * linear z-gradient on top of unity, so an isochromat at a known offset
     * experiences predictable off-resonance (no Helmholtz curvature guesswork).
     */
    private static SimulationConfig configWithZAxisOffResonance(
            ProjectRepository repo, SimulationConfig base, String name, double dBzPerMetre) {
        // Find the B0 field and replace its eigenfield with one that has a linear ramp.
        var b0Field = base.fields().stream().filter(f -> f.kind() == AmplitudeKind.STATIC).findFirst().orElseThrow();
        var b0Amplitude = b0Field.maxAmplitude();
        double normalisedSlope = dBzPerMetre / b0Amplitude;
        String script = String.format("return Vec3.of(0, 0, 1 + %s * z);", normalisedSlope);
        var eigen = new EigenfieldDocument(
            new ProjectNodeId("ef-test-" + name), name, "test off-resonance", script, "T", 1.0);
        repo.addEigenfield(eigen);

        var newFields = new ArrayList<FieldDefinition>(base.fields());
        int idx = newFields.indexOf(b0Field);
        newFields.set(idx, b0Field.withEigenfieldId(eigen.id()));
        return base.withFields(newFields);
    }

    @Test
    void fullFieldDefinitionAssertionsForLowFieldTemplate() {
        var session = new ProjectSessionViewModel();
        var doc = session.createSimConfig("CPMG-check",
            SimConfigTemplate.LOW_FIELD_MRI,
            ObjectFactory.PhysicsParams.DEFAULTS);
        var config = doc.config();

        FieldDefinition b0 = config.fields().stream().filter(f -> f.name().equals("B0")).findFirst().orElseThrow();
        FieldDefinition rf = config.fields().stream().filter(f -> f.name().equals("RF")).findFirst().orElseThrow();
        FieldDefinition gx = config.fields().stream().filter(f -> f.name().equals("Gradient X")).findFirst().orElseThrow();
        FieldDefinition gz = config.fields().stream().filter(f -> f.name().equals("Gradient Z")).findFirst().orElseThrow();

        assertEquals(AmplitudeKind.STATIC, b0.kind());
        assertEquals(AmplitudeKind.QUADRATURE, rf.kind());
        assertEquals(AmplitudeKind.REAL, gx.kind());
        assertEquals(AmplitudeKind.REAL, gz.kind());

        // Channel layout must match legacy [rf_I, rf_Q, gx, gz].
        var dynamicFields = config.fields().stream()
            .filter(f -> f.kind() != AmplitudeKind.STATIC).toList();
        assertEquals("RF", dynamicFields.get(0).name(), "RF must come first so controls[0,1] = (I, Q)");
        assertEquals("Gradient X", dynamicFields.get(1).name());
        assertEquals("Gradient Z", dynamicFields.get(2).name());

        double expectedLarmor = GAMMA * 0.0154 / (2 * Math.PI);
        assertEquals(expectedLarmor, rf.carrierHz(), 1.0);
        assertEquals(0.0154, config.referenceB0Tesla(), 1e-9);
    }

    @Test
    void ninetyXPulseMovesMagnetisationIntoTransversePlane() {
        BlochSimulator.clearCachesForTests();
        var session = new ProjectSessionViewModel();
        var doc = session.createSimConfig("CPMG-90",
            SimConfigTemplate.LOW_FIELD_MRI,
            ObjectFactory.PhysicsParams.DEFAULTS);
        var config = doc.config();
        var repo = session.repository.get();

        var train = buildCpmg(0);
        var data = BlochDataFactory.build(config, train.segments(), repo);

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

        // Sign convention sanity check: simulator integrates dM/dt = −γ M × B.
        // 90°x on thermal equilibrium → (0, −1, 0).
        assertEquals(-1.0, my, 0.05, "With the simulator's sign convention, My should be ~−1 after 90°x");
    }

    @Test
    void oneHundredEightyYPulseInvertsMxWhileKeepingMy() {
        BlochSimulator.clearCachesForTests();
        var session = new ProjectSessionViewModel();
        var doc = session.createSimConfig("CPMG-180Y",
            SimConfigTemplate.LOW_FIELD_MRI,
            ObjectFactory.PhysicsParams.DEFAULTS);
        var config = doc.config();
        var repo = session.repository.get();

        // Install a linear-z off-resonance so the spin dephases a predictable amount
        // during τ. dBz/dz = 50 µT/m · 10 mm · γ · 1 ms ≈ 0.13 rad. Small but finite.
        var configOff = configWithZAxisOffResonance(repo, config, "y-flip-off", 50e-6);

        var train = buildCpmg(1);
        var data = BlochDataFactory.build(configOff, train.segments(), repo);

        // 10 mm off isocentre → ~134 µrad/µs off-resonance → 0.13 rad in 1 ms.
        var trajectory = BlochSimulator.simulate(data, 0.0, 10.0, train.pulse());
        assertNotNull(trajectory);

        int[] b = segmentStepBoundaries(train.segments());
        // b[1]=after 90°x, b[2]=after τ, b[3]=after 180°y, b[4]=after 2τ.
        double mxBefore = trajectory.mxAt(b[2]);
        double myBefore = trajectory.myAt(b[2]);
        double mxAfter = trajectory.mxAt(b[3]);
        double myAfter = trajectory.myAt(b[3]);

        // Precondition: dephasing must be large enough that Mx ≠ 0, otherwise the test
        // trivially passes as "0 flipped to 0" and proves nothing.
        assertTrue(Math.abs(mxBefore) > 0.05,
            "Off-resonance produced negligible dephasing (Mx=" + mxBefore + "). " +
            "Check that the eigenfield's gradient is actually being applied.");

        // 180°y: (Mx, My, Mz) → (−Mx, My, −Mz). Mx flips, My is preserved.
        assertEquals(-mxBefore, mxAfter, 0.05,
            "180°y should flip Mx. Before=" + mxBefore + ", after=" + mxAfter);
        assertEquals(myBefore, myAfter, 0.05,
            "180°y should preserve My (rotation axis). Before=" + myBefore + ", after=" + myAfter);
    }

    @Test
    void cpmgEchoRefocusesDephasedEnsemble() {
        // The CPMG echo is an ensemble phenomenon. A single spin's |M⊥| stays ≈ 1
        // throughout (negligible T2 decay here). What refocuses is the COHERENT
        // SUM of many spins with different off-resonance offsets: they dephase
        // during τ, the 180°y pulse conjugates their phase, and they rephase at
        // t = 2τ into a peak of the coherent magnitude.
        BlochSimulator.clearCachesForTests();
        var session = new ProjectSessionViewModel();
        var doc = session.createSimConfig("CPMG-echo",
            SimConfigTemplate.LOW_FIELD_MRI,
            ObjectFactory.PhysicsParams.DEFAULTS);
        var config = doc.config();
        var repo = session.repository.get();

        // Linear-z off-resonance strong enough that spins dephase substantially
        // within τ. dBz/dz = 2 mT/m · ±10 mm · γ · 1 ms ≈ ±5.35 rad of spread —
        // an ensemble that ranges across this collapses to near-zero coherent
        // magnitude during free precession.
        var configOff = configWithZAxisOffResonance(repo, config, "echo-off", 2e-3);

        var train = buildCpmg(1);
        var data = BlochDataFactory.build(configOff, train.segments(), repo);

        double[] zSamples = {-10, -5, 0, 5, 10};
        var trajectories = new ArrayList<ax.xz.mri.model.simulation.Trajectory>();
        for (double z : zSamples) {
            var traj = BlochSimulator.simulate(data, 0.0, z, train.pulse());
            assertNotNull(traj);
            trajectories.add(traj);
        }

        int[] b = segmentStepBoundaries(train.segments());
        int stepCount = trajectories.get(0).pointCount();

        // Coherent sum |ΣM⊥| / N at each timestep.
        double ensembleAfterExcite = coherentMperp(trajectories, b[1]);
        double ensembleAfterFree = coherentMperp(trajectories, b[2]);

        // Search the full 2τ window after the 180° for the ensemble peak.
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
            "After τ of free precession with a ±5 rad phase spread the ensemble " +
            "should dephase to near-zero coherent |M⊥|. Got " + ensembleAfterFree);
        assertTrue(peakEnsemble > 0.95,
            "Ensemble echo peak should be close to 1 after refocusing. Got " + peakEnsemble);
        assertTrue(peakEnsemble > ensembleAfterFree + 0.1,
            "Echo peak should be clearly larger than the dephased signal. " +
            "Peak=" + peakEnsemble + ", pre-180 dephased=" + ensembleAfterFree);

        // The peak must be strictly inside the 2τ window.
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
        var session = new ProjectSessionViewModel();
        var doc = session.createSimConfig("CPMG-multi",
            SimConfigTemplate.LOW_FIELD_MRI,
            ObjectFactory.PhysicsParams.DEFAULTS);
        var config = doc.config();
        var repo = session.repository.get();
        var configOff = configWithZAxisOffResonance(repo, config, "multi-off", 200e-6);

        int nEchoes = 4;
        var train = buildCpmg(nEchoes);
        var data = BlochDataFactory.build(configOff, train.segments(), repo);
        var trajectory = BlochSimulator.simulate(data, 0.0, 10.0, train.pulse());
        assertNotNull(trajectory);

        int[] b = segmentStepBoundaries(train.segments());
        double pulse90CentreUs = b[1] * 0.5;
        double echoTimes[] = new double[nEchoes];
        for (int e = 0; e < nEchoes; e++) {
            int refocusSegment = 2 + 2 * e;
            double pulse180CentreUs = (b[refocusSegment] + 0.5 * (b[refocusSegment + 1] - b[refocusSegment])) * 1.0;
            double tauUs = pulse180CentreUs - pulse90CentreUs;
            echoTimes[e] = pulse180CentreUs + tauUs;
            pulse90CentreUs = pulse180CentreUs;  // next echo refocuses about the previous 180°
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
        // Regression: the old path would blow up heap for long sequences. This test
        // runs a CPMG with ~80 000 steps and a handful of points; it should complete
        // without OOM at the default test heap.
        BlochSimulator.clearCachesForTests();
        var session = new ProjectSessionViewModel();
        var doc = session.createSimConfig("CPMG-long",
            SimConfigTemplate.LOW_FIELD_MRI,
            ObjectFactory.PhysicsParams.DEFAULTS);
        var config = doc.config();
        var repo = session.repository.get();

        int nEchoes = 40;  // 40 × 2 ms = 80 ms total → ~80 000 steps per point
        var train = buildCpmg(nEchoes);
        var data = BlochDataFactory.build(config, train.segments(), repo);

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
