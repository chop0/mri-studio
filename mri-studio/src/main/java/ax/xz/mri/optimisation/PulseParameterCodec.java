package ax.xz.mri.optimisation;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;

import java.util.ArrayList;
import java.util.List;

/** Encodes and decodes optimiser pulse vectors. */
public final class PulseParameterCodec {
    private PulseParameterCodec() {
    }

    public static double[] flatten(List<PulseSegment> segments) {
        int length = segments.stream().mapToInt(segment -> segment.steps().size() * 5).sum();
        double[] flat = new double[length];
        int offset = 0;
        for (var segment : segments) {
            for (var step : segment.steps()) {
                flat[offset++] = step.b1x();
                flat[offset++] = step.b1y();
                flat[offset++] = step.gx();
                flat[offset++] = step.gz();
                flat[offset++] = step.rfGate();
            }
        }
        return flat;
    }

    public static List<PulseSegment> split(double[] flat, SequenceTemplate template) {
        var segments = new ArrayList<PulseSegment>(template.segments().size());
        int offset = 0;
        for (var spec : template.segments()) {
            var steps = new ArrayList<PulseStep>(spec.totalSteps());
            for (int stepIndex = 0; stepIndex < spec.totalSteps(); stepIndex++) {
                steps.add(new PulseStep(
                    flat[offset++],
                    flat[offset++],
                    flat[offset++],
                    flat[offset++],
                    flat[offset++]
                ));
            }
            segments.add(new PulseSegment(steps));
        }
        if (offset != flat.length) {
            throw new IllegalArgumentException("flat control length does not match sequence template");
        }
        return segments;
    }

    public static boolean[] defaultFreeMask(SequenceTemplate template, FreeMaskMode mode) {
        boolean[] mask = new boolean[template.flattenedLength()];
        if (mode == FreeMaskMode.NONE) return mask;
        int offset = 0;
        for (int segmentIndex = 0; segmentIndex < template.segments().size(); segmentIndex++) {
            var spec = template.segments().get(segmentIndex);
            boolean free = switch (mode) {
                case ALL -> true;
                case REFOCUSING_ONLY -> segmentIndex > 0;
                case NONE -> false;
            };
            for (int stepIndex = 0; stepIndex < spec.totalSteps(); stepIndex++) {
                for (int controlIndex = 0; controlIndex < spec.nCtrl(); controlIndex++) {
                    mask[offset++] = free;
                }
            }
        }
        return mask;
    }

    public static double[] defaultLowerBounds(SequenceTemplate template) {
        return fillBounds(template, true);
    }

    public static double[] defaultUpperBounds(SequenceTemplate template) {
        return fillBounds(template, false);
    }

    private static double[] fillBounds(SequenceTemplate template, boolean lower) {
        double[] bounds = new double[template.flattenedLength()];
        int offset = 0;
        for (var spec : template.segments()) {
            for (int stepIndex = 0; stepIndex < spec.totalSteps(); stepIndex++) {
                bounds[offset++] = lower ? -OptimisationHardwareLimits.B1_MAX : OptimisationHardwareLimits.B1_MAX;
                bounds[offset++] = lower ? -OptimisationHardwareLimits.B1_MAX : OptimisationHardwareLimits.B1_MAX;
                bounds[offset++] = lower ? -OptimisationHardwareLimits.GX_MAX : OptimisationHardwareLimits.GX_MAX;
                bounds[offset++] = lower ? -OptimisationHardwareLimits.GZ_MAX : OptimisationHardwareLimits.GZ_MAX;
                bounds[offset++] = lower ? 0.0 : 1.0;
            }
        }
        return bounds;
    }

    public static List<PulseSegment> copySegments(List<PulseSegment> segments) {
        return segments.stream().map(PulseParameterCodec::copySegment).toList();
    }

    public static PulseSegment copySegment(PulseSegment segment) {
        return new PulseSegment(segment.steps().stream()
            .map(step -> new PulseStep(step.b1x(), step.b1y(), step.gx(), step.gz(), step.rfGate()))
            .toList());
    }
}
