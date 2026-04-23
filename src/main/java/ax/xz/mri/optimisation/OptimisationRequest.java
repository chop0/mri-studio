package ax.xz.mri.optimisation;

import ax.xz.mri.model.sequence.PulseSegment;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runtime request for one optimiser run. */
public record OptimisationRequest(
    String seedScenarioName,
    String outputScenarioName,
    OptimisationProblem problem,
    List<PulseSegment> initialSegments,
    ControlParameterisation parameterisation,
    boolean[] freeMask,
    double[] lowerBounds,
    double[] upperBounds,
    ContinuationSchedule continuationSchedule,
    int snapshotEvery,
    AtomicBoolean stopRequested
) {
    public OptimisationRequest {
        initialSegments = PulseParameterCodec.copySegments(initialSegments);
        if (snapshotEvery <= 0) throw new IllegalArgumentException("snapshotEvery must be positive");
        if (freeMask.length != lowerBounds.length || freeMask.length != upperBounds.length) {
            throw new IllegalArgumentException("bounds and mask lengths must match");
        }
        stopRequested = stopRequested == null ? new AtomicBoolean(false) : stopRequested;
    }
}
