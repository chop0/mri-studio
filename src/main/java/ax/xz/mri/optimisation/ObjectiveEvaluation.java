package ax.xz.mri.optimisation;

import ax.xz.mri.model.simulation.SignalTrace;

/** Objective value and optional simulated trace from one evaluation. */
public record ObjectiveEvaluation(double value, SignalTrace signalTrace) {
}
