package ax.xz.mri.optimisation;

import ax.xz.mri.util.MathUtil;

import java.util.Arrays;

/** Pure Java L-BFGS-B solver, ported from the current Python/JAX algorithm. */
public final class LbfgsbSolver {
    public interface ObjectiveFunction {
        ValueGradient evaluate(double[] x);
    }

    public interface IterationListener {
        boolean onIteration(int iteration, double[] x, double value);
    }

    public record ValueGradient(double value, double[] gradient) {
    }

    public record SolverResult(double[] x, double value, int iterations, boolean success, String message) {
    }

    private final int historySize;
    private final int maxLineSearchSteps;
    private final double armijo;
    private final double gradientTolerance;

    public LbfgsbSolver() {
        this(10, 20, 1e-4, 1e-8);
    }

    public LbfgsbSolver(int historySize, int maxLineSearchSteps, double armijo, double gradientTolerance) {
        this.historySize = historySize;
        this.maxLineSearchSteps = maxLineSearchSteps;
        this.armijo = armijo;
        this.gradientTolerance = gradientTolerance;
    }

    public SolverResult solve(
        ObjectiveFunction function,
        double[] x0,
        double[] lowerBounds,
        double[] upperBounds,
        int maxIterations,
        IterationListener listener
    ) {
        int n = x0.length;
        double[] x = project(x0.clone(), lowerBounds, upperBounds);
        ValueGradient initial = function.evaluate(x);
        double value = initial.value();
        double[] gradient = initial.gradient().clone();
        double bestValue = value;
        double[] bestX = x.clone();

        double[][] sHistory = new double[historySize][n];
        double[][] yHistory = new double[historySize][n];
        double[] rhoHistory = new double[historySize];
        int stored = 0;

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            if (projectedGradientInfinityNorm(x, gradient, lowerBounds, upperBounds) < gradientTolerance) {
                return new SolverResult(bestX, bestValue, iteration - 1, true, "Projected gradient tolerance reached");
            }

            double[] direction = twoLoop(gradient, sHistory, yHistory, rhoHistory, stored);
            projectDirectionIntoBounds(direction, x, lowerBounds, upperBounds);

            double slope = dot(gradient, direction);
            if (slope >= 0.0) {
                direction = projectedSteepestDescent(gradient, x, lowerBounds, upperBounds);
                slope = dot(gradient, direction);
            }
            if (slope >= 0.0) {
                return new SolverResult(bestX, bestValue, iteration - 1, false, "No descent direction available");
            }

            double alpha = 1.0;
            ValueGradient candidate = null;
            double[] candidateX = null;
            boolean found = false;
            for (int lineStep = 0; lineStep < maxLineSearchSteps && alpha > 1e-15; lineStep++) {
                candidateX = project(addScaled(x, direction, alpha), lowerBounds, upperBounds);
                candidate = function.evaluate(candidateX);
                if (candidate.value() <= value + armijo * alpha * slope) {
                    found = true;
                    break;
                }
                alpha *= 0.5;
            }
            if (!found || candidate == null || candidateX == null) {
                return new SolverResult(bestX, bestValue, iteration - 1, false, "Line search failed");
            }

            double[] s = subtract(candidateX, x);
            double[] y = subtract(candidate.gradient(), gradient);
            double sy = dot(s, y);
            if (sy > 1e-10) {
                shiftDown(sHistory);
                shiftDown(yHistory);
                shiftDown(rhoHistory);
                sHistory[0] = s;
                yHistory[0] = y;
                rhoHistory[0] = 1.0 / sy;
                stored = Math.min(stored + 1, historySize);
            }

            x = candidateX;
            value = candidate.value();
            gradient = candidate.gradient().clone();
            if (value < bestValue) {
                bestValue = value;
                bestX = x.clone();
            }
            if (listener != null && listener.onIteration(iteration, x.clone(), value)) {
                return new SolverResult(bestX, bestValue, iteration, false, "Stopped by listener");
            }
        }
        return new SolverResult(bestX, bestValue, maxIterations, true, "Completed continuation stage");
    }

    private static double[] twoLoop(double[] gradient, double[][] sHistory, double[][] yHistory, double[] rhoHistory, int stored) {
        int n = gradient.length;
        double[] q = gradient.clone();
        double[] alpha = new double[sHistory.length];
        for (int index = 0; index < stored; index++) {
            alpha[index] = rhoHistory[index] * dot(sHistory[index], q);
            axpy(q, yHistory[index], -alpha[index]);
        }
        double gamma = 1.0;
        if (stored > 0) {
            double yy = dot(yHistory[0], yHistory[0]);
            gamma = yy < 1e-30 ? 1.0 : dot(sHistory[0], yHistory[0]) / yy;
        }
        for (int i = 0; i < n; i++) {
            q[i] *= gamma;
        }
        for (int index = stored - 1; index >= 0; index--) {
            double beta = rhoHistory[index] * dot(yHistory[index], q);
            axpy(q, sHistory[index], alpha[index] - beta);
        }
        for (int i = 0; i < n; i++) {
            q[i] = -q[i];
        }
        return q;
    }

    private static void projectDirectionIntoBounds(double[] direction, double[] x, double[] lowerBounds, double[] upperBounds) {
        for (int index = 0; index < direction.length; index++) {
            boolean atLower = x[index] <= lowerBounds[index] + 1e-10 && direction[index] < 0.0;
            boolean atUpper = x[index] >= upperBounds[index] - 1e-10 && direction[index] > 0.0;
            if (atLower || atUpper) direction[index] = 0.0;
        }
    }

    private static double[] projectedSteepestDescent(double[] gradient, double[] x, double[] lowerBounds, double[] upperBounds) {
        double[] direction = new double[gradient.length];
        for (int index = 0; index < gradient.length; index++) {
            direction[index] = -gradient[index];
            if (x[index] <= lowerBounds[index] + 1e-10 && direction[index] < 0.0) direction[index] = 0.0;
            if (x[index] >= upperBounds[index] - 1e-10 && direction[index] > 0.0) direction[index] = 0.0;
        }
        return direction;
    }

    private static double projectedGradientInfinityNorm(double[] x, double[] gradient, double[] lowerBounds, double[] upperBounds) {
        double max = 0.0;
        for (int index = 0; index < gradient.length; index++) {
            double value = gradient[index];
            if (x[index] <= lowerBounds[index] + 1e-10 && value > 0.0) value = 0.0;
            if (x[index] >= upperBounds[index] - 1e-10 && value < 0.0) value = 0.0;
            max = Math.max(max, Math.abs(value));
        }
        return max;
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0.0;
        for (int index = 0; index < a.length; index++) {
            sum += a[index] * b[index];
        }
        return sum;
    }

    private static void axpy(double[] target, double[] direction, double scale) {
        for (int index = 0; index < target.length; index++) {
            target[index] += scale * direction[index];
        }
    }

    private static double[] addScaled(double[] base, double[] direction, double scale) {
        double[] out = Arrays.copyOf(base, base.length);
        for (int index = 0; index < out.length; index++) {
            out[index] += scale * direction[index];
        }
        return out;
    }

    private static double[] subtract(double[] left, double[] right) {
        double[] out = new double[left.length];
        for (int index = 0; index < left.length; index++) {
            out[index] = left[index] - right[index];
        }
        return out;
    }

    private static double[] project(double[] x, double[] lowerBounds, double[] upperBounds) {
        for (int index = 0; index < x.length; index++) {
            x[index] = MathUtil.clamp(x[index], lowerBounds[index], upperBounds[index]);
        }
        return x;
    }

    private static void shiftDown(double[][] values) {
        for (int index = values.length - 1; index > 0; index--) {
            values[index] = values[index - 1];
        }
    }

    private static void shiftDown(double[] values) {
        for (int index = values.length - 1; index > 0; index--) {
            values[index] = values[index - 1];
        }
    }
}
