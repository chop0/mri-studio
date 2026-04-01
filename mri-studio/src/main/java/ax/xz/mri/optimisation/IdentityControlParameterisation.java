package ax.xz.mri.optimisation;

import ax.xz.mri.model.sequence.PulseSegment;

import java.util.List;

/** Non-periodic identity parameterisation. */
public record IdentityControlParameterisation(SequenceTemplate optimisationTemplate) implements ControlParameterisation {
    @Override
    public List<PulseSegment> expandSegments(List<PulseSegment> optimisedSegments) {
        return PulseParameterCodec.copySegments(optimisedSegments);
    }
}
