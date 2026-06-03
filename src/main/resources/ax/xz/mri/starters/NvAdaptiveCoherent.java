import module ax.xz.mri;
import static java.lang.Math.*;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/** Adaptive 2-point calibrated Ramsey scan; coarse-to-fine gradient warmup + I-optimal action selection + iterated EKF over a Lorentzian GP prior on Bz(x). */
class NvAdaptiveCoherent implements Script {

    static final double GAMMA = 2.0 * PI * 28.024e9;             // rad / (s · T)

    // GP prior on Bz(x): Lorentzian kernel, amplitude × half-width.
    static final double GP_AMP_T = 100e-9;
    static final double GP_DEPTH = 50e-9;
    static final double GP_REG   = 1e-9 * GP_AMP_T * GP_AMP_T;

    // Action grid: gradient strength × readout-axis phase.
    static final double GRAD_MAX_TPM = 5.0;
    static final int    N_GRAD       = 101;
    static final double[] THETAS     = {0.0, 0.5 * PI};

    // This scenario is a faithful port of the reference adaptive_gradient_1d
    // notebook (same 32-NV layout, same Lorentzian B_true, same GP prior). The
    // one number that isn't copied verbatim is SHOTS: the notebook uses 1600,
    // but its readout noise is an idealised per-shot Poisson, whereas this
    // simulator's photon-counting model integrates a finite read window and so
    // counts ≈12× fewer photons per shot. What actually drives the result is the
    // measurement noise σ_M (∝ 1/√(effective photons)); matching the notebook's
    // σ_M ≈ 0.497 (mean-M units, at its 1600 shots) takes ≈19 000 nominal shots
    // here. SHOTS only scales the *injected* noise — the sim runs one
    // deterministic Ramsey block per measurement regardless — so this is free at
    // runtime and reproduces the notebook's reconstruction quality.
    static final long   SHOTS    = 19_000L;
    static final int    N_ITER   = 10_000;
    static final int    N_EVAL   = 64;

    // Two-phase τ warmup (coarse-to-fine). The Ramsey readout
    // M = (1/N) Σ sin(γτ(B + g·x) + θ) is periodic in the phase γτ(B + g·x): a
    // gradient large enough to wrap that phase by more than 2π across the NV
    // span aliases the measurement, and the I-optimal scorer *favours* those
    // large gradients (biggest linearised Jacobian) — so at a fixed long τ the
    // linearised EKF locks onto a wrapped, wrong mode unless the gradient is
    // crippled (and crippling it throws away the spatial resolution the gradient
    // is there to provide). Instead we run the first N_WARMUP iterations at a
    // SHORT τ, where the same physical gradient produces a (τ_long/τ_short)×
    // smaller phase so the FULL gradient range stays unambiguous *and* spatially
    // resolving; the posterior settles into the correct basin on coarse, alias-
    // free, spatially-informative measurements. We then switch to the long τ for
    // high precision (its large γτ amplifies the per-shot information once the
    // basin is locked). This is the standard multi-τ phase-estimation ladder —
    // measurably better than ramping a gradient ceiling at a fixed long τ.
    static final double TAU_SHORT_S = 8e-6;
    static final double TAU_LONG_S  = 100e-6;
    static final int    N_WARMUP    = 1000;

    // Ramsey pulse template.
    static final double MW_PI_HALF_AMP_T = 89.21e-6;             // γ·B·t = π/2 at t = 100 ns
    static final double T_PI_HALF_S = 100e-9, MW_DT_S = 1e-9;
    static final double T_PUMP_S   = 1.0e-6, T_READ_S = 300e-9;
    static final double LASER_DT_S = 50e-9,  SETTLE_DT_S = 10e-9;

    static final String READ_MARK = "read-start";

    public void run(ScriptContext ctx) throws InterruptedException {
        // Resolve every source + probe we'll need up-front — typos throw
        // here, not mid-loop, and the rest of the script references each
        // channel through a typed handle with the offset baked in.
        SourceKey mwI   = ctx.source("MW I");
        SourceKey mwQ   = ctx.source("MW Q");
        SourceKey gradX = ctx.source("Grad X");
        SourceKey laser = ctx.source("Laser");
        ProbeKey  redCounter = ctx.probe("Red counter");

        var centres = ctx.substances().stream()
            .filter(NvEnsemble.class::isInstance)
            .map(NvEnsemble.class::cast)
            .flatMap(nv -> nv.centres().stream())
            .toList();
        int N = centres.size();
        double[] xNv = new double[N], yNv = new double[N], zNv = new double[N];
        for (int i = 0; i < N; i++) {
            var c = centres.get(i);
            xNv[i] = c.xMetres(); yNv[i] = c.yMetres(); zNv[i] = c.zMetres();
        }
        ctx.log("Using " + N + " NV positions from the substance");

        double xMin = xNv[0], xMax = xNv[0];
        for (double x : xNv) { xMin = min(xMin, x); xMax = max(xMax, x); }
        double pad = 0.02 * (xMax - xMin + 1e-12);
        double[] xEval = linspace(xMin - pad, xMax + pad, N_EVAL);
        double yEval = mean(yNv), zEval = mean(zNv);

        // Two-point bright/dark calibration absorbs every device-side
        // scale (QE, dark counts, pump-during-read leakage) and lets
        // the same code path work on hardware as well as the sim.
        double cBright = runAndIntegrate(ctx, redCounter, buildBrightCalibration(ctx, laser));
        double cDark   = runAndIntegrate(ctx, redCounter, buildDarkCalibration(ctx, laser, mwI, mwQ));
        double baseline = 0.5 * (cBright + cDark);
        double contrast = 0.5 * (cBright - cDark);
        double sigmaM = sqrt(baseline / SHOTS) / contrast;
        double sigmaMSq = sigmaM * sigmaM;
        ctx.log(String.format(
            "Calibration: bright = %.3f clicks/shot, dark = %.3f clicks/shot, σ_M = %.3e (N=%d, %d shots)",
            cBright, cDark, sigmaM, N, SHOTS));

        // Truth snapshot, used only for live diagnostic overlays.
        double[] truthAtNvs  = new double[N];
        for (int i = 0; i < N; i++) truthAtNvs[i] = ctx.staticBzAt(xNv[i], yNv[i], zNv[i]);
        double[] truthAtEval = ctx.staticBzAlongX(xEval, yEval, zEval);

        double[][] Knn    = gramMatrix(xNv);
        double[][] KnnInv = invertSpd(Knn);
        double[][] Ken    = crossMatrix(xEval, xNv);
        double[][] Pmap   = matMul(Ken, KnnInv);
        double[]   gpVar  = computeGpVar(Ken, KnnInv, xEval.length, N);
        double[][] W      = matMul(transpose(Pmap), Pmap);

        double[][] lambda    = cloneSquare(KnnInv);
        double[]   eta       = new double[N];
        double[]   mu        = new double[N];
        double[][] fisherAcc = cloneSquare(KnnInv);

        double[] rmseHist    = new double[N_ITER];
        double[] sigmaHist   = new double[N_ITER];
        double[] shannonHist = new double[N_ITER];
        double[] tauHist     = new double[N_ITER];
        double[] gradHist    = new double[N_ITER];
        double[] phaseHist   = new double[N_ITER];

        SplittableRandom rng = new SplittableRandom(ctx.random().nextLong());

        for (int iter = 0; iter < N_ITER; iter++) {
            ctx.checkpoint();
            double[][] sigma = invertSpd(lambda);

            // Two-phase τ: short (alias-free at the full gradient range) during
            // the warmup, then long (high-precision) once the posterior has
            // locked the correct basin. See the TAU_SHORT/LONG comment above.
            double tau = (iter < N_WARMUP) ? TAU_SHORT_S : TAU_LONG_S;

            double bestScore = Double.NEGATIVE_INFINITY;
            double bestG = 0, bestTheta = 0;
            double[] bestA = null;
            for (double g : linspace(-GRAD_MAX_TPM, +GRAD_MAX_TPM, N_GRAD)) {
                for (double theta : THETAS) {
                    double[] a = linearisedA(mu, xNv, tau, g, theta);
                    double[] sigmaA = matVec(sigma, a);
                    double score = quadForm(sigmaA, W) / (sigmaMSq + dot(a, sigmaA));
                    if (score > bestScore) {
                        bestScore = score; bestG = g; bestTheta = theta; bestA = a;
                    }
                }
            }

            double Mobs = runRamseyAndDecode(ctx, rng, redCounter,
                laser, mwI, mwQ, gradX,
                baseline, contrast, tau, bestG, bestTheta);

            // Information-form linearised Kalman update at the current μ.
            double hMu = sumOfSins(mu, xNv, tau, bestG, bestTheta) / N;
            double yLin = Mobs - hMu + dot(bestA, mu);
            rank1UpdateSym(lambda, bestA, 1.0 / sigmaMSq);
            for (int i = 0; i < N; i++) eta[i] += bestA[i] * yLin / sigmaMSq;
            System.arraycopy(solveSpd(lambda, eta), 0, mu, 0, N);

            double[] denseVar   = denseVarianceFromPosterior(Pmap, invertSpd(lambda), gpVar);
            double[] denseSigma = sqrtEach(denseVar);
            double[] BpostDense = matVec(Pmap, mu);
            double   sigmaDense = sqrt(mean(denseVar));
            double   rmse       = rmse(BpostDense, truthAtEval);

            double[] aTruth = linearisedA(truthAtNvs, xNv, tau, bestG, bestTheta);
            rank1UpdateSym(fisherAcc, aTruth, 1.0 / sigmaMSq);
            double sigmaShannon = sqrt(mean(
                denseVarianceFromPosterior(Pmap, invertSpd(fisherAcc), gpVar)));

            tauHist[iter]     = tau;
            gradHist[iter]    = bestG;
            phaseHist[iter]   = bestTheta;
            rmseHist[iter]    = rmse;
            sigmaHist[iter]   = sigmaDense;
            shannonHist[iter] = sigmaShannon;

            ctx.status("iter " + iter);
            ctx.progress(iter + 1, N_ITER);
            ctx.metric("rmse", rmse);
            ctx.metric("sigma", sigmaDense);
            ctx.metric("shannon", sigmaShannon);
            ctx.metric("grad", bestG);

            int n = iter + 1;
            double[] xs = new double[n];
            double[] rmseY = new double[n], sigmaY = new double[n];
            for (int i = 0; i < n; i++) {
                xs[i] = i; rmseY[i] = rmseHist[i]; sigmaY[i] = sigmaHist[i];
            }
            ctx.show(new Visualisation.Line("convergence", "Convergence",
                "iteration", "RMSE (T)", List.of(
                    new Visualisation.Line.Series("rmse", xs, rmseY),
                    new Visualisation.Line.Series("σ posterior", xs, sigmaY))));

            ctx.show(new Visualisation.Line("posterior", "Posterior vs ground truth — B(x)",
                "x (m)", "Bz (T)", List.of(
                    new Visualisation.Line.Series("truth Bz", xEval, truthAtEval),
                    new Visualisation.Line.Series("posterior μ ± σ", xEval, BpostDense, denseSigma))));
        }

        ctx.put("xNv",         xNv);
        ctx.put("xEval",       xEval);
        ctx.put("posteriorMu", mu.clone());
        ctx.put("posteriorB",  matVec(Pmap, mu));
        ctx.put("truthAtEval", truthAtEval);
        ctx.put("rmseHist",    rmseHist);
        ctx.put("sigmaHist",   sigmaHist);
        ctx.put("shannonHist", shannonHist);
        ctx.put("tauHist",     tauHist);
        ctx.put("gradHist",    gradHist);
        ctx.put("phaseHist",   phaseHist);
        ctx.summary(String.format(
            "converged in %d iters — rmse %.3e T, sigma %.3e T",
            N_ITER, rmseHist[N_ITER - 1], sigmaHist[N_ITER - 1]));
    }

    // Ramsey forward model: M = (1/N) · Σ sin(γτ(B_n + g·x_n) + θ_r).

    static double sumOfSins(double[] mu, double[] xNv, double tau, double g, double theta) {
        double sum = 0, gt = GAMMA * tau;
        for (int i = 0; i < mu.length; i++) sum += sin(gt * (mu[i] + g * xNv[i]) + theta);
        return sum;
    }

    static double[] linearisedA(double[] mu, double[] xNv, double tau, double g, double theta) {
        int N = mu.length;
        double[] a = new double[N];
        double gt = GAMMA * tau;
        for (int i = 0; i < N; i++) a[i] = gt * cos(gt * (mu[i] + g * xNv[i]) + theta) / N;
        return a;
    }

    // Sequence dispatch — same shape for sim and hardware.

    static double runRamseyAndDecode(ScriptContext ctx, SplittableRandom rng, ProbeKey red,
                                     SourceKey laser, SourceKey mwI, SourceKey mwQ, SourceKey gradX,
                                     double baseline, double contrast,
                                     double tau, double g, double thetaR) throws InterruptedException {
        var seq = buildRamseySequence(ctx, laser, mwI, mwQ, gradX, tau, g, thetaR);
        double cleanClicksPerShot = runAndIntegrate(ctx, red, seq);
        double mean = cleanClicksPerShot * SHOTS;
        double noisy = mean + gaussian(rng) * sqrt(max(mean, 1e-30));
        return (baseline - noisy / SHOTS) / contrast;
    }

    static double runAndIntegrate(ScriptContext ctx, ProbeKey red, BakedSequence seq)
            throws InterruptedException {
        var trace = ctx.observationSource().run(seq);
        double startSec = seq.markedTime(READ_MARK);
        double endSec = startSec + T_READ_S;
        double total = 0;
        for (var p : trace.read(red).points()) {
            double tSec = p.tMicros() * 1e-6;
            if (tSec >= startSec && tSec <= endSec) total += p.real();
        }
        return total;
    }

    static BakedSequence buildBrightCalibration(ScriptContext ctx, SourceKey laser) {
        var b = ctx.newSequence();
        b.hold(T_PUMP_S, LASER_DT_S, laser, 1.0);
        b.gap(SETTLE_DT_S);
        b.mark(READ_MARK);
        b.hold(T_READ_S, LASER_DT_S, laser, 1.0);
        return b.build();
    }

    static BakedSequence buildDarkCalibration(ScriptContext ctx,
                                               SourceKey laser, SourceKey mwI, SourceKey mwQ) {
        var b = ctx.newSequence();
        b.hold(T_PUMP_S, LASER_DT_S, laser, 1.0);
        b.gap(SETTLE_DT_S);
        b.rf(2 * T_PI_HALF_S, MW_DT_S, Map.of(mwI, MW_PI_HALF_AMP_T, mwQ, 0.0));
        b.gap(SETTLE_DT_S);
        b.mark(READ_MARK);
        b.hold(T_READ_S, LASER_DT_S, laser, 1.0);
        return b.build();
    }

    /*
     * Axis convention: first π/2 around +x takes +z → -y.
     * Free precession by γτ(B+g·x) leaves the state at (sin, -cos, 0).
     * Second π/2 around (sin θ_r, cos θ_r, 0) gives sz_final = -sin(γτ(B+g·x) + θ_r).
     * Decoded M = -avg(sz) = +(1/N) Σ sin(...), matching sumOfSins / linearisedA.
     */
    static BakedSequence buildRamseySequence(ScriptContext ctx,
                                              SourceKey laser, SourceKey mwI, SourceKey mwQ, SourceKey gradX,
                                              double tau, double gradTPerM, double thetaR) {
        var b = ctx.newSequence();
        b.hold(T_PUMP_S, LASER_DT_S, laser, 1.0);
        b.gap(SETTLE_DT_S);
        b.rf(T_PI_HALF_S, MW_DT_S, Map.of(mwI, MW_PI_HALF_AMP_T, mwQ, 0.0));
        // One big dt = τ free-precession step; the NV kernel evolves analytically.
        b.hold(tau, tau, gradX, gradTPerM);
        b.rf(T_PI_HALF_S, MW_DT_S,
            Map.of(mwI, MW_PI_HALF_AMP_T * sin(thetaR),
                   mwQ, MW_PI_HALF_AMP_T * cos(thetaR)));
        b.gap(SETTLE_DT_S);
        b.mark(READ_MARK);
        b.hold(T_READ_S, LASER_DT_S, laser, 1.0);
        return b.build();
    }

    // Lorentzian GP prior k(Δx) = A² · z² / (z² + Δx²), z = 2·depth.

    static double kernel(double dx) {
        double z2 = 4.0 * GP_DEPTH * GP_DEPTH;
        return GP_AMP_T * GP_AMP_T * z2 / (z2 + dx * dx);
    }

    static double[][] gramMatrix(double[] xNv) {
        int n = xNv.length;
        double[][] K = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) K[i][j] = kernel(xNv[i] - xNv[j]);
            K[i][i] += GP_REG;
        }
        return K;
    }

    static double[][] crossMatrix(double[] xEval, double[] xNv) {
        double[][] K = new double[xEval.length][xNv.length];
        for (int e = 0; e < xEval.length; e++) {
            for (int n = 0; n < xNv.length; n++) K[e][n] = kernel(xEval[e] - xNv[n]);
        }
        return K;
    }

    static double[] computeGpVar(double[][] Ken, double[][] KnnInv, int nEval, int nNv) {
        double k0 = kernel(0.0);
        double[][] tmp = matMul(Ken, KnnInv);
        double[] gpVar = new double[nEval];
        for (int e = 0; e < nEval; e++) {
            double s = 0;
            for (int n = 0; n < nNv; n++) s += tmp[e][n] * Ken[e][n];
            gpVar[e] = max(k0 - s, 0);
        }
        return gpVar;
    }

    static double[] denseVarianceFromPosterior(double[][] Pmap, double[][] sigmaPost, double[] gpVar) {
        double[] out = new double[Pmap.length];
        for (int e = 0; e < Pmap.length; e++) {
            out[e] = max(dot(Pmap[e], matVec(sigmaPost, Pmap[e])), 0) + gpVar[e];
        }
        return out;
    }

    // Box–Muller Gaussian (drop the spare).
    static double gaussian(SplittableRandom rng) {
        double u1 = max(rng.nextDouble(), 1e-300);
        double u2 = rng.nextDouble();
        return sqrt(-2.0 * log(u1)) * cos(2.0 * PI * u2);
    }

    // Linear algebra for small N (dense Cholesky).

    static double[] linspace(double lo, double hi, int n) {
        double[] xs = new double[n];
        if (n == 1) { xs[0] = 0.5 * (lo + hi); return xs; }
        double step = (hi - lo) / (n - 1);
        for (int i = 0; i < n; i++) xs[i] = lo + i * step;
        return xs;
    }

    static double[] sqrtEach(double[] xs) {
        double[] out = new double[xs.length];
        for (int i = 0; i < xs.length; i++) out[i] = sqrt(xs[i]);
        return out;
    }

    static double[][] cloneSquare(double[][] A) {
        double[][] out = new double[A.length][];
        for (int i = 0; i < A.length; i++) out[i] = A[i].clone();
        return out;
    }

    static double[][] transpose(double[][] M) {
        double[][] T = new double[M[0].length][M.length];
        for (int i = 0; i < M.length; i++)
            for (int j = 0; j < M[0].length; j++) T[j][i] = M[i][j];
        return T;
    }

    static void choleskyInPlace(double[][] A) {
        int n = A.length;
        for (int j = 0; j < n; j++) {
            double diag = A[j][j];
            for (int k = 0; k < j; k++) diag -= A[j][k] * A[j][k];
            if (!(diag > 0)) throw new IllegalStateException(
                "Cholesky failed at row " + j + " (pivot = " + diag + ")");
            double ljj = sqrt(diag);
            A[j][j] = ljj;
            double inv = 1.0 / ljj;
            for (int i = j + 1; i < n; i++) {
                double s = A[i][j];
                for (int k = 0; k < j; k++) s -= A[i][k] * A[j][k];
                A[i][j] = s * inv;
            }
        }
    }

    static void forwardSolve(double[][] L, double[] b) {
        for (int i = 0; i < b.length; i++) {
            double s = b[i];
            for (int k = 0; k < i; k++) s -= L[i][k] * b[k];
            b[i] = s / L[i][i];
        }
    }

    static void backwardSolveT(double[][] L, double[] y) {
        for (int i = y.length - 1; i >= 0; i--) {
            double s = y[i];
            for (int k = i + 1; k < y.length; k++) s -= L[k][i] * y[k];
            y[i] = s / L[i][i];
        }
    }

    static double[] solveSpd(double[][] A, double[] b) {
        double[][] L = cloneSquare(A);
        choleskyInPlace(L);
        double[] x = b.clone();
        forwardSolve(L, x);
        backwardSolveT(L, x);
        return x;
    }

    static double[][] invertSpd(double[][] A) {
        int n = A.length;
        double[][] L = cloneSquare(A);
        choleskyInPlace(L);
        double[][] inv = new double[n][n];
        double[] col = new double[n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) col[i] = (i == j ? 1.0 : 0.0);
            forwardSolve(L, col);
            backwardSolveT(L, col);
            for (int i = 0; i < n; i++) inv[i][j] = col[i];
        }
        return inv;
    }

    static double[] matVec(double[][] M, double[] v) {
        double[] y = new double[M.length];
        for (int i = 0; i < M.length; i++) {
            double s = 0;
            double[] row = M[i];
            for (int j = 0; j < v.length; j++) s += row[j] * v[j];
            y[i] = s;
        }
        return y;
    }

    static double[][] matMul(double[][] A, double[][] B) {
        int m = A.length, k = B.length, n = B[0].length;
        double[][] C = new double[m][n];
        for (int i = 0; i < m; i++) {
            double[] Ai = A[i], Ci = C[i];
            for (int p = 0; p < k; p++) {
                double a = Ai[p];
                if (a == 0) continue;
                double[] Bp = B[p];
                for (int j = 0; j < n; j++) Ci[j] += a * Bp[j];
            }
        }
        return C;
    }

    static double dot(double[] v, double[] u) {
        double s = 0;
        for (int i = 0; i < v.length; i++) s += v[i] * u[i];
        return s;
    }

    static double quadForm(double[] v, double[][] M) {
        double s = 0;
        for (int i = 0; i < v.length; i++) {
            double[] Mi = M[i];
            double row = 0;
            for (int j = 0; j < v.length; j++) row += Mi[j] * v[j];
            s += v[i] * row;
        }
        return s;
    }

    static void rank1UpdateSym(double[][] A, double[] v, double scale) {
        int n = v.length;
        for (int i = 0; i < n; i++) {
            double vi = v[i];
            if (vi == 0) continue;
            double[] Ai = A[i];
            double si = scale * vi;
            for (int j = 0; j < n; j++) Ai[j] += si * v[j];
        }
    }

    static double mean(double[] xs) {
        double s = 0;
        for (double x : xs) s += x;
        return s / xs.length;
    }

    static double rmse(double[] estimate, double[] truth) {
        double s = 0;
        for (int i = 0; i < estimate.length; i++) {
            double d = estimate[i] - truth[i];
            s += d * d;
        }
        return sqrt(s / estimate.length);
    }

    void main() { NMRStudio.runScript(new NvAdaptiveCoherent()); }
}
