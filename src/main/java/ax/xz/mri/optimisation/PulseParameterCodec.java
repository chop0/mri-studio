package ax.xz.mri.optimisation;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodes and decodes optimiser pulse vectors.
 *
 * <p>Each time step flattens to {@code nCtrl} scalars, where
 * {@code nCtrl = controls.length + 1}: the field-channel amplitudes followed
 * by the RF gate. The channel layout per step matches the field order in
 * the associated {@link ax.xz.mri.model.simulation.SimulationConfig} — each
 * {@code REAL} field contributes one scalar, each {@code QUADRATURE} field
 * two (I, Q), each {@code STATIC} field none.
 */
public final class PulseParameterCodec {
    private PulseParameterCodec() {
    }

    public static double[] flatten(List<PulseSegment> segments) {
        int length = 0;
        for (var segment : segments) {
            for (var step : segment.steps()) {
                length += step.channelCount() + 1;
            }
        }
        double[] flat = new double[length];
        int offset = 0;
        for (var segment : segments) {
            for (var step : segment.steps()) {
                for (var v : step.controls()) flat[offset++] = v;
                flat[offset++] = step.rfGate();
            }
        }
        return flat;
    }

    public static List<PulseSegment> split(double[] flat, SequenceTemplate template) {
        var segments = new ArrayList<PulseSegment>(template.segments().size());
        int offset = 0;
        for (var spec : template.segments()) {
            int nCtrl = spec.nCtrl();
            int stepControls = nCtrl - 1;
            var steps = new ArrayList<PulseStep>(spec.totalSteps());
            for (int stepIndex = 0; stepIndex < spec.totalSteps(); stepIndex++) {
                var controls = new double[stepControls];
                for (int k = 0; k < stepControls; k++) controls[k] = flat[offset++];
                double gate = flat[offset++];
                steps.add(new PulseStep(controls, gate));
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

    /**
     * Per-control lower bounds derived from field amplitude limits and an
     * RF-gate bound of 0. {@code channelBounds} has one {@code [min, max]} pair
     * per non-gate channel in step order; the RF gate bound is always
     * {@code [0, 1]}.
     */
    public static double[] defaultLowerBounds(SequenceTemplate template, double[][] channelBounds) {
        return fillBounds(template, channelBounds, true);
    }

    public static double[] defaultUpperBounds(SequenceTemplate template, double[][] channelBounds) {
        return fillBounds(template, channelBounds, false);
    }

    private static double[] fillBounds(SequenceTemplate template, double[][] channelBounds, boolean lower) {
        double[] bounds = new double[template.flattenedLength()];
        int offset = 0;
        for (var spec : template.segments()) {
            int nCtrl = spec.nCtrl();
            int stepControls = nCtrl - 1;
            if (channelBounds != null && channelBounds.length != stepControls) {
                throw new IllegalArgumentException(
                    "channelBounds length (" + channelBounds.length + ") must match controls per step ("
                    + stepControls + ")");
            }
            for (int stepIndex = 0; stepIndex < spec.totalSteps(); stepIndex++) {
                for (int k = 0; k < stepControls; k++) {
                    double[] pair = channelBounds != null ? channelBounds[k] : new double[]{-1.0, 1.0};
                    bounds[offset++] = lower ? pair[0] : pair[1];
                }
                bounds[offset++] = lower ? 0.0 : 1.0;
            }
        }
        return bounds;
    }

    public static List<PulseSegment> copySegments(List<PulseSegment> segments) {
        return segments.stream().map(PulseParameterCodec::copySegment).toList();
    }

    public static PulseSegment copySegment(PulseSegment segment) {
        return new PulseSegment(segment.steps().stream().map(PulseStep::copy).toList());
    }
}
