package ax.xz.mri.model.procedure;

import ax.xz.mri.dsl.BakedSequence;
import ax.xz.mri.dsl.ProbeKey;
import ax.xz.mri.dsl.Script;
import ax.xz.mri.dsl.ScriptContext;
import ax.xz.mri.dsl.SourceKey;
import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.SimulationCompiler;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.model.substance.NvEnsemble;
import ax.xz.mri.service.procedure.ScriptHarness;
import ax.xz.mri.service.procedure.SimulatorObservationSource;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.ui.wizard.starters.SimConfigTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static java.lang.Math.PI;
import static java.lang.Math.sin;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Diagnostic: does the Java <em>simulator</em>'s Ramsey readout match the
 * analytic forward model the adaptive-coherent EKF assumes,
 * {@code M = (1/N) Σ sin(γτ(B_n + g·x_n) + θ)}?
 *
 * <p>Drives single Ramsey measurements through the exact production path
 * (ScriptContext + SimulatorObservationSource + the same sequence builder),
 * sweeping gradient g and readout phase θ, and compares each measured M to the
 * analytic prediction using the sim's own staticBz at the NV positions. A scale
 * ≠ 1, a phase offset, or nonlinearity in g exposes a simulation-side defect.
 */
final class NvForwardModelProbeTest {

    @Test
    void simulatorRamseyMatchesAnalyticForwardModel() throws Exception {
        var built = SimConfigTemplate.NV_CENTRE_DIAMOND.buildCircuit(ProjectState.empty(), "fwd-probe");
        var repo = ProjectState.empty();
        for (var ef : built.newEigenfields()) repo = repo.withEigenfield(ef);
        for (var sub : built.newSubstances()) repo = repo.withSubstance(sub);
        repo = repo.withCircuit(built.circuit());

        var cfg = new SimulationConfig(
            SimConfigTemplate.NV_CENTRE_DIAMOND.referenceB0Tesla(),
            SimConfigTemplate.NV_CENTRE_DIAMOND.defaultPhysics().dtSeconds(),
            built.circuit().id());

        int channels = 0;
        for (var c : built.circuit().components())
            if (c instanceof CircuitComponent.VoltageSource s) channels += s.kind().channelCount();
        var pulse = List.of(new PulseSegment(List.of(new PulseStep(new double[Math.max(channels, 1)], 0.0))));
        var segments = List.of(new Segment(1.0e-6, 0, 1));
        var sim = new SimulationCompiler().compile(cfg, segments, pulse, repo);
        var obs = new SimulatorObservationSource(cfg, repo);

        var probe = new Probe();
        try (var harness = new ScriptHarness()) {
            harness.run(probe, sim, obs, 1L, t -> {}).get();
        }

        double[] g = probe.gOut, m = probe.mMeas, a = probe.mAnaly;
        double[] th = probe.thetaOut;
        System.out.printf("%nForward-model probe — %d NVs, τ=%.0f µs%n", probe.nNv, probe.tauUsed * 1e6);
        System.out.printf("%-8s %-8s %12s %12s %10s%n", "g(T/m)", "θ", "M_sim", "M_analytic", "ratio");
        double sMM = 0, sMA = 0, sAA = 0, sErr2 = 0;
        for (int i = 0; i < g.length; i++) {
            double ratio = Math.abs(a[i]) > 1e-3 ? m[i] / a[i] : Double.NaN;
            System.out.printf("%-8.2f %-8s %12.5f %12.5f %10.3f%n",
                g[i], th[i] == 0 ? "0" : "π/2", m[i], a[i], ratio);
            sMM += m[i] * m[i]; sMA += m[i] * a[i]; sAA += a[i] * a[i];
            sErr2 += (m[i] - a[i]) * (m[i] - a[i]);
        }
        double bestScale = sMA / sAA;                 // least-squares M_sim ≈ scale · M_analytic
        double residRms = Math.sqrt(sErr2 / g.length);
        double residAfterScale = 0;
        for (int i = 0; i < g.length; i++) {
            double d = m[i] - bestScale * a[i];
            residAfterScale += d * d;
        }
        residAfterScale = Math.sqrt(residAfterScale / g.length);
        System.out.printf("%nBest-fit scale  M_sim ≈ %.4f · M_analytic%n", bestScale);
        System.out.printf("RMS residual (raw)          = %.4f%n", residRms);
        System.out.printf("RMS residual (after scale)  = %.4f%n", residAfterScale);
        System.out.printf("→ %s%n", bestScale > 0.9 && residRms < 0.05
            ? "forward model is FAITHFUL (scale≈1, low residual)"
            : "forward model DEVIATES (see scale / residual) — simulation-side issue");

        // The simulator's Ramsey readout must equal the analytic model the EKF
        // assumes: M = (1/N) Σ sin(γτ(B_n + g·x_n) + θ). A scale ≠ 1 would mean
        // lost contrast / pulse-fidelity error; a large residual would mean a
        // nonlinear gradient or off-resonance carrier. Either would bias the EKF.
        assertTrue(Math.abs(bestScale - 1.0) < 0.05,
            "simulator Ramsey contrast must match the analytic model (scale=" + bestScale + ")");
        assertTrue(residRms < 0.02,
            "simulator M must track analytic M across the (g, θ) sweep (RMS residual=" + residRms + ")");

        // Readout-noise parity with the reference notebook. The script's σ_M
        // (mean-M units) = sqrt(baseline/SHOTS)/contrast. The notebook's σ_M at
        // 1600 shots / 32 NVs is 0.4974 (mean units). Report the SHOTS at which
        // the sim's σ_M matches the notebook's — the photon-counting model emits
        // a different absolute click scale than the notebook's idealised Poisson.
        double pythonSigmaM = 0.4974;
        for (long shots : new long[]{1600, 7000, 50000}) {
            double sigmaM = Math.sqrt(probe.baseline / shots) / probe.contrast;
            System.out.printf("σ_M @ %6d shots = %.4f  (notebook = %.4f)%n", shots, sigmaM, pythonSigmaM);
        }
        double sigma1600 = Math.sqrt(probe.baseline / 1600.0) / probe.contrast;
        long shotsForParity = Math.round(1600 * (sigma1600 / pythonSigmaM) * (sigma1600 / pythonSigmaM));
        System.out.printf("→ SHOTS for σ_M parity with the notebook ≈ %d%n", shotsForParity);
    }

    /** Probe script: sweeps (g, θ) and records simulator M vs analytic M. */
    static final class Probe implements Script {
        static final double GAMMA = 2.0 * PI * 28.024e9;
        static final double TAU_S = 100e-6;
        static final double MW_PI_HALF_AMP_T = 89.21e-6;
        static final double T_PI_HALF_S = 100e-9, MW_DT_S = 1e-9;
        static final double T_PUMP_S = 1.0e-6, T_READ_S = 300e-9;
        static final double LASER_DT_S = 50e-9, SETTLE_DT_S = 10e-9;
        static final String READ_MARK = "read-start";

        static final double[] GS = {-4, -2, -1, -0.5, -0.2, 0, 0.2, 0.5, 1, 2, 4};
        static final double[] THETAS = {0.0, 0.5 * PI};

        double[] gOut, thetaOut, mMeas, mAnaly;
        int nNv;
        double tauUsed = TAU_S;
        double baseline, contrast;

        public void run(ScriptContext ctx) throws InterruptedException {
            SourceKey mwI = ctx.source("MW I"), mwQ = ctx.source("MW Q");
            SourceKey gradX = ctx.source("Grad X"), laser = ctx.source("Laser");
            ProbeKey red = ctx.probe("Red counter");

            var centres = ctx.substances().stream()
                .filter(NvEnsemble.class::isInstance).map(NvEnsemble.class::cast)
                .flatMap(nv -> nv.centres().stream()).toList();
            int N = centres.size();
            nNv = N;
            double[] x = new double[N], B = new double[N];
            for (int i = 0; i < N; i++) {
                var c = centres.get(i);
                x[i] = c.xMetres();
                B[i] = ctx.staticBzAt(c.xMetres(), c.yMetres(), c.zMetres());
            }

            double cBright = integrate(ctx, red, bright(ctx, laser));
            double cDark = integrate(ctx, red, dark(ctx, laser, mwI, mwQ));
            double baseline = 0.5 * (cBright + cDark), contrast = 0.5 * (cBright - cDark);

            this.baseline = baseline;
            this.contrast = contrast;

            int n = GS.length * THETAS.length;
            gOut = new double[n]; thetaOut = new double[n]; mMeas = new double[n]; mAnaly = new double[n];
            int k = 0;
            for (double theta : THETAS) {
                for (double g : GS) {
                    double clean = integrate(ctx, red, ramsey(ctx, laser, mwI, mwQ, gradX, TAU_S, g, theta));
                    double mSim = (baseline - clean) / contrast;
                    double s = 0;
                    for (int i = 0; i < N; i++) s += sin(GAMMA * TAU_S * (B[i] + g * x[i]) + theta);
                    gOut[k] = g; thetaOut[k] = theta; mMeas[k] = mSim; mAnaly[k] = s / N;
                    k++;
                }
            }
            ctx.put("done", true);
        }

        static double integrate(ScriptContext ctx, ProbeKey red, BakedSequence seq) throws InterruptedException {
            var trace = ctx.observationSource().run(seq);
            double start = seq.markedTime(READ_MARK), end = start + T_READ_S;
            double total = 0;
            for (var p : trace.read(red).points()) {
                double t = p.tMicros() * 1e-6;
                if (t >= start && t <= end) total += p.real();
            }
            return total;
        }

        static BakedSequence bright(ScriptContext ctx, SourceKey laser) {
            var b = ctx.newSequence();
            b.hold(T_PUMP_S, LASER_DT_S, laser, 1.0);
            b.gap(SETTLE_DT_S);
            b.mark(READ_MARK);
            b.hold(T_READ_S, LASER_DT_S, laser, 1.0);
            return b.build();
        }

        static BakedSequence dark(ScriptContext ctx, SourceKey laser, SourceKey mwI, SourceKey mwQ) {
            var b = ctx.newSequence();
            b.hold(T_PUMP_S, LASER_DT_S, laser, 1.0);
            b.gap(SETTLE_DT_S);
            b.rf(2 * T_PI_HALF_S, MW_DT_S, Map.of(mwI, MW_PI_HALF_AMP_T, mwQ, 0.0));
            b.gap(SETTLE_DT_S);
            b.mark(READ_MARK);
            b.hold(T_READ_S, LASER_DT_S, laser, 1.0);
            return b.build();
        }

        static BakedSequence ramsey(ScriptContext ctx, SourceKey laser, SourceKey mwI, SourceKey mwQ,
                                    SourceKey gradX, double tau, double g, double thetaR) {
            var b = ctx.newSequence();
            b.hold(T_PUMP_S, LASER_DT_S, laser, 1.0);
            b.gap(SETTLE_DT_S);
            b.rf(T_PI_HALF_S, MW_DT_S, Map.of(mwI, MW_PI_HALF_AMP_T, mwQ, 0.0));
            b.hold(tau, tau, gradX, g);
            b.rf(T_PI_HALF_S, MW_DT_S,
                Map.of(mwI, MW_PI_HALF_AMP_T * sin(thetaR), mwQ, MW_PI_HALF_AMP_T * Math.cos(thetaR)));
            b.gap(SETTLE_DT_S);
            b.mark(READ_MARK);
            b.hold(T_READ_S, LASER_DT_S, laser, 1.0);
            return b.build();
        }
    }
}
