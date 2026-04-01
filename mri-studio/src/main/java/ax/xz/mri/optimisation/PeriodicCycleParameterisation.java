package ax.xz.mri.optimisation;

import ax.xz.mri.model.sequence.PulseSegment;

import java.util.ArrayList;
import java.util.List;

/** Expands a reduced prefix + cycle segment list into a repeated full train. */
public record PeriodicCycleParameterisation(SequenceTemplate optimisationTemplate) implements ControlParameterisation {
    public PeriodicCycleParameterisation {
        if (!optimisationTemplate.periodic()) {
            throw new IllegalArgumentException("PeriodicCycleParameterisation requires a periodic template");
        }
    }

    @Override
    public List<PulseSegment> expandSegments(List<PulseSegment> optimisedSegments) {
        if (optimisedSegments.size() != optimisationTemplate.reducedSegmentCount()) {
            throw new IllegalArgumentException("optimisedSegments size does not match reduced template");
        }
        var expanded = new ArrayList<PulseSegment>(optimisationTemplate.expandedSegmentCount());
        for (int index = 0; index < optimisationTemplate.prefixSegmentCount(); index++) {
            expanded.add(PulseParameterCodec.copySegment(optimisedSegments.get(index)));
        }
        var cycle = optimisedSegments.subList(
            optimisationTemplate.prefixSegmentCount(),
            optimisationTemplate.reducedSegmentCount()
        );
        for (int repeat = 0; repeat < optimisationTemplate.cycleRepeatCount(); repeat++) {
            for (var segment : cycle) {
                expanded.add(PulseParameterCodec.copySegment(segment));
            }
        }
        return expanded;
    }
}
