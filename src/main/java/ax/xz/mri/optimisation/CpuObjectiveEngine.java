package ax.xz.mri.optimisation;

import ax.xz.mri.model.sequence.PulseSegment;

import java.util.List;

/** Pure Java CPU objective engine. */
public class CpuObjectiveEngine extends BlochObjectiveEngine {
    private final double finiteDifferenceFactor;

    public CpuObjectiveEngine() {
        this(DEFAULT_EPSILON_FACTOR);
    }

    public CpuObjectiveEngine(double finiteDifferenceFactor) {
        this.finiteDifferenceFactor = finiteDifferenceFactor;
    }

    @Override
    public double[] gradient(OptimisationProblem problem, List<PulseSegment> segments) {
        double[] base = PulseParameterCodec.flatten(segments);
        double[] gradient = new double[base.length];
        double epsilon = Math.max(finiteDifferenceFactor, 1e-12);
        for (int index = 0; index < base.length; index++) {
            double scale = Math.max(Math.abs(base[index]), 1.0);
            double h = scale * epsilon;
            double original = base[index];
            base[index] = original + h;
            double upper = evaluateInternal(problem, PulseParameterCodec.split(base, problem.sequenceTemplate()), false).value();
            base[index] = original - h;
            double lower = evaluateInternal(problem, PulseParameterCodec.split(base, problem.sequenceTemplate()), false).value();
            base[index] = original;
            gradient[index] = (upper - lower) / (2.0 * h);
        }
        return gradient;
    }
}
