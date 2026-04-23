package ax.xz.mri.optimisation;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.SignalTrace;

import java.util.List;

/** Final result of one optimiser run. */
public record OptimisationResult(
    List<PulseSegment> optimisedSegments,
    List<PulseSegment> expandedSegments,
    SnapshotSeries snapshots,
    SignalTrace signalTrace,
    int iterations,
    int evaluations,
    double bestValue,
    boolean success,
    String message
) {
}
