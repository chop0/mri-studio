package ax.xz.mri.optimisation;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.SignalTrace;

import java.util.List;

/** Evaluates optimisation objectives and gradients for pulse trains. */
public interface ObjectiveEngine {
    ObjectiveEvaluation evaluate(OptimisationProblem problem, List<PulseSegment> segments);

    double[] gradient(OptimisationProblem problem, List<PulseSegment> segments);

    SignalTrace simulateSignal(OptimisationProblem problem, List<PulseSegment> segments);
}
