import module ax.xz.mri;
import static java.lang.Math.*;
import java.util.Arrays;

/** L-BFGS-B over K hard-pulse blocks; objective minimises -|Mxy|² at the end of the pulse train (so the optimum tips into the transverse plane). */
class PulseOptimisation implements Script {

    // Pulse train: K blocks of duration DT, each parameterised by (amplitude, phase).
    static final int    K_PULSES = 16;
    static final double DT       = 5e-6;
    static final double B1_MAX   = 50e-6;

    // Bloch evolution.
    static final double T1       = 1.0;
    static final double T2       = 0.1;
    static final double GAMMA    = 2.6752e8;      // proton γ
    static final double DELTA_BZ = 0.0;

    // L-BFGS-B hyper-parameters.
    static final int    HISTORY_SIZE    = 10;
    static final int    MAX_LINE_SEARCH = 20;
    static final double ARMIJO          = 1e-4;
    static final double GRAD_TOL        = 1e-8;
    static final double FD_EPS          = 1e-4;
    static final int    MAX_ITER        = 80;

    public void run(ScriptContext ctx) throws InterruptedException {
        ctx.status("Initialising " + K_PULSES + "-block hard-pulse train");
        int n = 2 * K_PULSES;
        State s = new State(n);
        for (int k = 0; k < K_PULSES; k++) {
            s.x[k] = 0.3 * B1_MAX;
            s.lo[k] = -B1_MAX; s.hi[k] = +B1_MAX;
            s.lo[K_PULSES + k] = -10.0 * PI;
            s.hi[K_PULSES + k] = +10.0 * PI;
        }
        project(s.x, s.lo, s.hi);
        evaluateInto(s.x, s);
        s.bestValue = s.value;
        System.arraycopy(s.x, 0, s.bestX, 0, n);
        ctx.metric("objective", s.value);

        for (int iter = 0; iter < MAX_ITER; iter++) {
            ctx.checkpoint();
            ctx.status("iter " + iter);
            ctx.progress(iter, MAX_ITER);

            if (projectedGradInfNorm(s.x, s.gradient, s.lo, s.hi) < GRAD_TOL) {
                s.terminated = true; s.terminationReason = "gradient tolerance reached";
                break;
            }

            double[] direction = twoLoop(s);
            projectDirectionIntoBounds(direction, s.x, s.lo, s.hi);
            double slope = dot(s.gradient, direction);
            if (slope >= 0) {
                direction = projectedSteepestDescent(s.gradient, s.x, s.lo, s.hi);
                slope = dot(s.gradient, direction);
            }
            if (slope >= 0) {
                s.terminated = true; s.terminationReason = "no descent direction";
                break;
            }

            double alpha = 1.0;
            double[] candidateX = null, candidateGrad = null;
            double candidateValue = Double.POSITIVE_INFINITY;
            boolean accepted = false;
            for (int ls = 0; ls < MAX_LINE_SEARCH && alpha > 1e-15; ls++) {
                candidateX = project(addScaled(s.x, direction, alpha), s.lo, s.hi);
                Eval e = evaluate(candidateX);
                candidateValue = e.value();
                candidateGrad = e.gradient();
                if (candidateValue <= s.value + ARMIJO * alpha * slope) { accepted = true; break; }
                alpha *= 0.5;
            }
            if (!accepted) {
                s.terminated = true; s.terminationReason = "line search failed";
                break;
            }

            double[] sVec = subtract(candidateX, s.x);
            double[] yVec = subtract(candidateGrad, s.gradient);
            double sy = dot(sVec, yVec);
            if (sy > 1e-10) {
                shiftDown(s.sHistory); shiftDown(s.yHistory); shiftDown(s.rhoHistory);
                s.sHistory[0] = sVec; s.yHistory[0] = yVec; s.rhoHistory[0] = 1.0 / sy;
                s.stored = min(s.stored + 1, HISTORY_SIZE);
            }

            System.arraycopy(candidateX, 0, s.x, 0, n);
            System.arraycopy(candidateGrad, 0, s.gradient, 0, n);
            s.value = candidateValue;
            if (candidateValue < s.bestValue) {
                s.bestValue = candidateValue;
                System.arraycopy(candidateX, 0, s.bestX, 0, n);
            }

            ctx.metric("objective", s.value);
            ctx.metric("|grad|",    projectedGradInfNorm(s.x, s.gradient, s.lo, s.hi));
            ctx.metric("step",      alpha);
        }

        double[] amplitudes = Arrays.copyOfRange(s.bestX, 0, K_PULSES);
        double[] phases     = Arrays.copyOfRange(s.bestX, K_PULSES, 2 * K_PULSES);
        ctx.put("amplitudes_T", amplitudes);
        ctx.put("phases_rad",   phases);
        ctx.put("dt_per_block", DT);
        ctx.put("objective",    s.bestValue);
        ctx.summary(String.format(
            "L-BFGS-B finished: best objective %.6e (%s)",
            s.bestValue, s.terminationReason == null ? "iteration budget" : s.terminationReason));
    }

    static final class State {
        final double[] x, lo, hi, gradient, bestX;
        double value, bestValue;
        final double[][] sHistory = new double[HISTORY_SIZE][];
        final double[][] yHistory = new double[HISTORY_SIZE][];
        final double[]   rhoHistory = new double[HISTORY_SIZE];
        int stored = 0;
        boolean terminated = false;
        String terminationReason = null;

        State(int n) {
            x = new double[n]; lo = new double[n]; hi = new double[n];
            gradient = new double[n]; bestX = new double[n];
        }
    }

    record Eval(double value, double[] gradient) {}

    // Hard-pulse propagator: K Rodrigues rotations + per-block T1/T2 relaxation, then return -|Mxy|².
    static double bloch(double[] x) {
        double mx = 0, my = 0, mz = 1;
        double e1 = exp(-DT / T1), e2 = exp(-DT / T2);
        for (int k = 0; k < K_PULSES; k++) {
            double amp = x[k], ph = x[K_PULSES + k];
            double[] m = rodriguesStep(amp * cos(ph), amp * sin(ph), DELTA_BZ, mx, my, mz, e1, e2);
            mx = m[0]; my = m[1]; mz = m[2];
        }
        return -(mx * mx + my * my);
    }

    static double[] rodriguesStep(double bx, double by, double bz,
                                  double mx, double my, double mz, double e1, double e2) {
        double bMag = sqrt(bx * bx + by * by + bz * bz);
        if (bMag < 1e-30) return new double[]{ mx * e2, my * e2, mz * e1 + (1.0 - e1) };
        double theta = GAMMA * bMag * DT;
        double cosT = cos(theta), sinT = sin(theta), oneMC = 1.0 - cosT;
        double kx = bx / bMag, ky = by / bMag, kz = bz / bMag;
        double rx = mx * (cosT + kx*kx*oneMC) + my * (kx*ky*oneMC - kz*sinT) + mz * (kx*kz*oneMC + ky*sinT);
        double ry = mx * (ky*kx*oneMC + kz*sinT) + my * (cosT + ky*ky*oneMC) + mz * (ky*kz*oneMC - kx*sinT);
        double rz = mx * (kz*kx*oneMC - ky*sinT) + my * (kz*ky*oneMC + kx*sinT) + mz * (cosT + kz*kz*oneMC);
        return new double[]{ rx * e2, ry * e2, rz * e1 + (1.0 - e1) };
    }

    // Finite-difference central derivative of bloch wrt each parameter.
    static Eval evaluate(double[] x) {
        double v = bloch(x);
        double[] grad = new double[x.length];
        double[] xp = x.clone();
        for (int i = 0; i < x.length; i++) {
            double h = max(abs(x[i]), 1.0) * FD_EPS;
            double orig = xp[i];
            xp[i] = orig + h; double up = bloch(xp);
            xp[i] = orig - h; double down = bloch(xp);
            xp[i] = orig;
            grad[i] = (up - down) / (2.0 * h);
        }
        return new Eval(v, grad);
    }

    static void evaluateInto(double[] x, State s) {
        Eval e = evaluate(x);
        s.value = e.value();
        System.arraycopy(e.gradient(), 0, s.gradient, 0, s.gradient.length);
    }

    // L-BFGS two-loop recursion.
    static double[] twoLoop(State s) {
        int n = s.gradient.length;
        double[] q = s.gradient.clone();
        double[] alpha = new double[HISTORY_SIZE];
        for (int i = 0; i < s.stored; i++) {
            alpha[i] = s.rhoHistory[i] * dot(s.sHistory[i], q);
            axpy(q, s.yHistory[i], -alpha[i]);
        }
        double gamma = 1.0;
        if (s.stored > 0) {
            double yy = dot(s.yHistory[0], s.yHistory[0]);
            gamma = yy < 1e-30 ? 1.0 : dot(s.sHistory[0], s.yHistory[0]) / yy;
        }
        for (int i = 0; i < n; i++) q[i] *= gamma;
        for (int i = s.stored - 1; i >= 0; i--) {
            double beta = s.rhoHistory[i] * dot(s.yHistory[i], q);
            axpy(q, s.sHistory[i], alpha[i] - beta);
        }
        for (int i = 0; i < n; i++) q[i] = -q[i];
        return q;
    }

    static void projectDirectionIntoBounds(double[] d, double[] x, double[] lo, double[] hi) {
        for (int i = 0; i < d.length; i++) {
            if ((x[i] <= lo[i] + 1e-10 && d[i] < 0) || (x[i] >= hi[i] - 1e-10 && d[i] > 0)) d[i] = 0;
        }
    }

    static double[] projectedSteepestDescent(double[] g, double[] x, double[] lo, double[] hi) {
        double[] d = new double[g.length];
        for (int i = 0; i < g.length; i++) {
            d[i] = -g[i];
            if ((x[i] <= lo[i] + 1e-10 && d[i] < 0) || (x[i] >= hi[i] - 1e-10 && d[i] > 0)) d[i] = 0;
        }
        return d;
    }

    static double projectedGradInfNorm(double[] x, double[] g, double[] lo, double[] hi) {
        double m = 0;
        for (int i = 0; i < g.length; i++) {
            double v = g[i];
            if (x[i] <= lo[i] + 1e-10 && v > 0) v = 0;
            if (x[i] >= hi[i] - 1e-10 && v < 0) v = 0;
            m = max(m, abs(v));
        }
        return m;
    }

    static double[] addScaled(double[] base, double[] dir, double a) {
        double[] out = base.clone();
        for (int i = 0; i < out.length; i++) out[i] += a * dir[i];
        return out;
    }

    static double[] subtract(double[] a, double[] b) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] - b[i];
        return out;
    }

    static void axpy(double[] target, double[] dir, double a) {
        for (int i = 0; i < target.length; i++) target[i] += a * dir[i];
    }

    static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    static double[] project(double[] x, double[] lo, double[] hi) {
        for (int i = 0; i < x.length; i++) {
            if (x[i] < lo[i]) x[i] = lo[i];
            if (x[i] > hi[i]) x[i] = hi[i];
        }
        return x;
    }

    static void shiftDown(double[][] v) { for (int i = v.length - 1; i > 0; i--) v[i] = v[i - 1]; }
    static void shiftDown(double[] v)   { for (int i = v.length - 1; i > 0; i--) v[i] = v[i - 1]; }

    void main() { NMRStudio.runScript(new PulseOptimisation()); }
}
