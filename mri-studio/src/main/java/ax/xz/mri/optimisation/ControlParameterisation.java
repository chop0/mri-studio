package ax.xz.mri.optimisation;

import ax.xz.mri.model.sequence.PulseSegment;

import java.util.List;
import java.util.Map;

/** Maps optimiser-facing segments to exported full pulse trains. */
public interface ControlParameterisation {
    SequenceTemplate optimisationTemplate();

    List<PulseSegment> expandSegments(List<PulseSegment> optimisedSegments);

    default SnapshotSeries expandSnapshots(Map<Integer, List<PulseSegment>> snapshots) {
        var expanded = new java.util.TreeMap<Integer, List<PulseSegment>>();
        for (var entry : snapshots.entrySet()) {
            expanded.put(entry.getKey(), expandSegments(entry.getValue()));
        }
        return new SnapshotSeries(expanded);
    }
}
