package ax.xz.mri.optimisation;

import ax.xz.mri.model.sequence.PulseSegment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Snapshot pulse trains keyed by completed iteration count. */
public record SnapshotSeries(Map<Integer, List<PulseSegment>> snapshots) {
    public SnapshotSeries {
        var ordered = new LinkedHashMap<Integer, List<PulseSegment>>();
        new TreeMap<>(snapshots).forEach(ordered::put);
        snapshots = Collections.unmodifiableMap(ordered);
    }
}
