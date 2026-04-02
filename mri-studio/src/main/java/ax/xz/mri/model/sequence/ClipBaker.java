package ax.xz.mri.model.sequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Bakes a {@link ClipSequence} into flat {@link Segment}/{@link PulseSegment} arrays
 * that the MRI simulator can consume.
 *
 * <p>The baking process evaluates every clip at each discrete time step, summing
 * overlapping clips per channel (additive semantics), and produces a single segment
 * spanning the entire sequence duration.
 */
public final class ClipBaker {
    private ClipBaker() {}

    /** Result of baking: the segment timing info and its pulse data. */
    public record BakeResult(List<Segment> segments, List<PulseSegment> pulseSegments) {}

    /**
     * Bake a clip sequence into simulator-compatible segments and pulse data.
     *
     * <p>Produces a single segment covering the full duration. The time step {@code dt}
     * is taken from the clip sequence. RF gate is evaluated as a continuous channel;
     * steps where gate >= 0.5 are considered RF-on.
     */
    public static BakeResult bake(ClipSequence seq) {
        if (seq == null || seq.totalDuration() <= 0 || seq.dt() <= 0) {
            return new BakeResult(List.of(), List.of());
        }

        double dtMicros = seq.dt();
        double dtSeconds = dtMicros * 1e-6;
        int totalSteps = seq.totalSteps();
        var clips = seq.clips();

        // Evaluate every step
        var steps = new ArrayList<PulseStep>(totalSteps);
        for (int i = 0; i < totalSteps; i++) {
            double t = i * dtMicros;
            double b1x = ClipEvaluator.evaluateChannel(clips, SignalChannel.B1X, t);
            double b1y = ClipEvaluator.evaluateChannel(clips, SignalChannel.B1Y, t);
            double gx  = ClipEvaluator.evaluateChannel(clips, SignalChannel.GX, t);
            double gz  = ClipEvaluator.evaluateChannel(clips, SignalChannel.GZ, t);
            double gate = ClipEvaluator.evaluateChannel(clips, SignalChannel.RF_GATE, t);
            steps.add(new PulseStep(b1x, b1y, gx, gz, gate));
        }

        // Count RF-on and free-precession steps
        int nPulse = 0;
        for (var step : steps) {
            if (step.isRfOn()) nPulse++;
        }
        int nFree = totalSteps - nPulse;

        // Single segment spanning the entire duration
        var segment = new Segment(dtSeconds, nFree, nPulse);
        var pulseSegment = new PulseSegment(List.copyOf(steps));

        return new BakeResult(List.of(segment), List.of(pulseSegment));
    }

    /**
     * Convert a legacy segment-based sequence into a clip sequence.
     *
     * <p>Each non-zero channel region in the step data is converted to a CONSTANT clip.
     * This provides a starting point for editing legacy/imported sequences in the clip editor.
     */
    public static ClipSequence fromLegacy(List<Segment> segments, List<PulseSegment> pulse) {
        if (segments.isEmpty() || pulse.isEmpty()) {
            return new ClipSequence(10.0, 300.0, List.of());
        }

        // Compute total duration and use the first segment's dt
        double dt = segments.getFirst().dt() * 1e6; // convert to μs
        double totalDuration = 0;
        for (var seg : segments) {
            totalDuration += seg.totalSteps() * seg.dt() * 1e6;
        }

        // Walk through all steps and extract contiguous non-zero regions per channel
        var clips = new ArrayList<SignalClip>();
        var channels = SignalChannel.values();

        double time = 0;
        for (int segIdx = 0; segIdx < segments.size() && segIdx < pulse.size(); segIdx++) {
            var seg = segments.get(segIdx);
            var steps = pulse.get(segIdx).steps();
            double segDt = seg.dt() * 1e6;

            for (int stepIdx = 0; stepIdx < steps.size(); stepIdx++) {
                var step = steps.get(stepIdx);
                double stepTime = time + stepIdx * segDt;

                for (var ch : channels) {
                    double value = channelValue(step, ch);
                    if (Math.abs(value) > 1e-15) {
                        // Find the extent of this constant-value run
                        int runEnd = stepIdx + 1;
                        while (runEnd < steps.size() &&
                               Math.abs(channelValue(steps.get(runEnd), ch) - value) < 1e-15) {
                            runEnd++;
                        }
                        double clipDuration = (runEnd - stepIdx) * segDt;
                        clips.add(new SignalClip(
                            null, ch, ClipShape.CONSTANT,
                            stepTime, clipDuration, value,
                            null, null
                        ));
                        // Skip ahead (outer loop will continue from runEnd for other channels,
                        // but we need to track per-channel — this is simplified)
                    }
                }
            }
            time += steps.size() * segDt;
        }

        // Deduplicate — the simple approach above may create overlapping clips
        // For legacy conversion, a simpler approach: one constant clip per step per non-zero channel
        // Actually, let's do a cleaner per-channel sweep
        clips.clear();
        time = 0;
        for (var ch : channels) {
            double channelTime = 0;
            for (int segIdx = 0; segIdx < segments.size() && segIdx < pulse.size(); segIdx++) {
                var seg = segments.get(segIdx);
                var steps = pulse.get(segIdx).steps();
                double segDt = seg.dt() * 1e6;

                int stepIdx = 0;
                while (stepIdx < steps.size()) {
                    double value = channelValue(steps.get(stepIdx), ch);
                    if (Math.abs(value) < 1e-15) {
                        stepIdx++;
                        continue;
                    }
                    // Start of a non-zero region — find its extent
                    int runStart = stepIdx;
                    while (stepIdx < steps.size() &&
                           Math.abs(channelValue(steps.get(stepIdx), ch) - value) < 1e-15) {
                        stepIdx++;
                    }
                    double clipStart = channelTime + runStart * segDt;
                    double clipDuration = (stepIdx - runStart) * segDt;
                    clips.add(new SignalClip(
                        null, ch, ClipShape.CONSTANT,
                        clipStart, clipDuration, value,
                        null, null
                    ));
                }
                channelTime += steps.size() * segDt;
            }
        }

        return new ClipSequence(dt, totalDuration, clips);
    }

    private static double channelValue(PulseStep step, SignalChannel ch) {
        return switch (ch) {
            case B1X -> step.b1x();
            case B1Y -> step.b1y();
            case GX  -> step.gx();
            case GZ  -> step.gz();
            case RF_GATE -> step.rfGate();
        };
    }
}
