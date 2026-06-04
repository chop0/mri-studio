package ax.xz.mri.starters;

import module ax.xz.mri;
import static java.lang.Math.*;
import java.util.SplittableRandom;

/** Non-adaptive k-space scanner: Var(M) over the gradient g peaks at Q = γτg = ±k_p, the sample's spatial wavevector. */
class NvKSpaceSweep implements Script {

    static final int    N_NV   = 32;
    static final double X_HALF = 1.0e-6;
    static final double Y_NV   = 0.0;
    static final double Z_NV   = 100e-9;

    static final double TAU_SEC = 50e-6;
    static final double G_MAX   = 8.0;
    static final int    N_G     = 81;
    static final int    SAMPLES_PER_G = 24;

    // δBz(x_n) = AMP · sin(2π·k_p·x_n + φ), φ uniform on [0, 2π) per sample.
    static final double K_P_INV_M   = 5.0e6;
    static final double DELTA_AMP_T = 100e-9;

    static final double GAMMA       = 2.0 * PI * 28.024e9;
    static final long   SHOTS       = 50_000L;
    static final double PL_BRIGHT   = 0.030;
    static final double PL_DARK     = 0.027;
    static final double PL_BASELINE = 1.0;

    public void run(ScriptContext ctx) throws InterruptedException {
        CompiledSimulation sim = ctx.simulation();
        if (sim == null) ctx.log("simulation() is null — running with zero baseline field");

        double[] xNv  = linspace(-X_HALF, X_HALF, N_NV);
        double[] baseB = readTruthBz(sim, xNv, Y_NV, Z_NV);

        double[] gradients    = linspace(-G_MAX, +G_MAX, N_G);
        double[] qWavevectors = new double[N_G];
        double[] meanM        = new double[N_G];
        double[] varM         = new double[N_G];
        double[] meanMSq      = new double[N_G];

        double sigmaM = sigmaM();
        SplittableRandom rng = new SplittableRandom(ctx.random().nextLong());
        ctx.status(String.format("Sweeping %d gradients × %d samples (τ = %.0f µs)",
            N_G, SAMPLES_PER_G, TAU_SEC * 1e6));

        for (int gi = 0; gi < N_G; gi++) {
            ctx.checkpoint();
            double g = gradients[gi];
            qWavevectors[gi] = GAMMA * TAU_SEC * g / (2.0 * PI);

            double sumM = 0, sumMM = 0;
            for (int s = 0; s < SAMPLES_PER_G; s++) {
                double phi = 2.0 * PI * rng.nextDouble();
                double[] sampledB = new double[N_NV];
                for (int i = 0; i < N_NV; i++) {
                    sampledB[i] = baseB[i] + DELTA_AMP_T * sin(2.0 * PI * K_P_INV_M * xNv[i] + phi);
                }
                double M = ramseyM(sampledB, xNv, TAU_SEC, g, 0.0) + sigmaM * gaussian(rng);
                sumM  += M;
                sumMM += M * M;
            }
            double mean = sumM / SAMPLES_PER_G;
            meanM[gi]   = mean;
            varM[gi]    = (SAMPLES_PER_G > 1)
                ? (sumMM - SAMPLES_PER_G * mean * mean) / (SAMPLES_PER_G - 1) : 0;
            meanMSq[gi] = sumMM / SAMPLES_PER_G;

            ctx.metric("g_Tpm",  g);
            ctx.metric("Q_perm", qWavevectors[gi]);
            ctx.metric("Var(M)", varM[gi]);
            ctx.progress(gi + 1, N_G);
        }

        int peakIdx = 0;
        for (int i = 1; i < N_G; i++) if (varM[i] > varM[peakIdx]) peakIdx = i;
        double peakQ = qWavevectors[peakIdx];

        ctx.show(Visualisation.Line.of("var-m",
            "Var(M) vs Q", "Q (cycles/m)", "Var(M)", qWavevectors, varM));
        ctx.show(new Visualisation.Scalar("peak-q", "Peak Q", peakQ, "cycles/m"));
        ctx.log(String.format(
            "Peak Var(M) = %.3e at Q = %.3e cycles/m (truth k_p = %.3e cycles/m)",
            varM[peakIdx], peakQ, K_P_INV_M));

        ctx.put("gradients_Tpm", gradients);
        ctx.put("Q_cyclesPerM",  qWavevectors);
        ctx.put("meanM",         meanM);
        ctx.put("Var(M)",        varM);
        ctx.put("meanMSquared",  meanMSq);
        ctx.put("peakQ",         peakQ);
        ctx.put("truth_kp",      K_P_INV_M);
        ctx.summary(String.format(
            "swept %d gradients, peak |Var(M)| at Q = %.3e (truth %.3e cycles/m)",
            N_G, peakQ, K_P_INV_M));
    }

    static double ramseyM(double[] B, double[] xNv, double tau, double g, double theta) {
        double sum = 0, gt = GAMMA * tau;
        for (int i = 0; i < B.length; i++) sum += sin(gt * (B[i] + g * xNv[i]) + theta);
        return sum / B.length;
    }

    static double sigmaM() {
        double contrast = PL_BRIGHT - PL_DARK;
        double meanPL = 0.5 * (PL_BRIGHT + PL_DARK) * PL_BASELINE;
        return sqrt(meanPL / SHOTS) / contrast;
    }

    static double gaussian(SplittableRandom rng) {
        double u1 = max(rng.nextDouble(), 1e-300);
        double u2 = rng.nextDouble();
        return sqrt(-2.0 * log(u1)) * cos(2.0 * PI * u2);
    }

    static double[] readTruthBz(CompiledSimulation sim, double[] xs, double y, double z) {
        double[] out = new double[xs.length];
        if (sim == null) return out;
        for (int i = 0; i < xs.length; i++) out[i] = sim.sampleAt(Vec3.of(xs[i], y, z)).staticBz();
        return out;
    }

    static double[] linspace(double lo, double hi, int n) {
        double[] xs = new double[n];
        if (n == 1) { xs[0] = 0.5 * (lo + hi); return xs; }
        double step = (hi - lo) / (n - 1);
        for (int i = 0; i < n; i++) xs[i] = lo + i * step;
        return xs;
    }

    void main() { NMRStudio.runScript(new NvKSpaceSweep()); }
}
