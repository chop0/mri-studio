package ax.xz.mri.model.sequence;

import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.DrivePath;
import ax.xz.mri.model.simulation.SimulationConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Bakes a {@link ClipSequence} into flat {@link Segment}/{@link PulseSegment}
 * arrays consumed by the simulator.
 *
 * <p>Walks the time axis at {@code seq.dt()}, evaluates each output channel by
 * summing every clip whose track routes to it, and emits one
 * {@link PulseSegment} spanning the full duration. Per-step
 * {@link PulseStep#controls()} is sized to
 * {@link SimulationConfig#totalChannelCount()} in {@link DrivePath} order.
 *
 * <p>{@link PulseStep#rfGate()} is an optimisation hint auto-computed from the
 * running magnitude of every {@link AmplitudeKind#QUADRATURE QUADRATURE} drive
 * path; the simulator's explicit T/R gating comes from
 * {@link AmplitudeKind#GATE GATE} paths wired to coils.
 */
public final class ClipBaker {
    public static final double RF_GATE_THRESHOLD = 1e-12;

    private ClipBaker() {}

    public record BakeResult(List<Segment> segments, List<PulseSegment> pulseSegments) {}

    public static BakeResult bake(ClipSequence seq, SimulationConfig config) {
        if (seq == null || seq.totalDuration() <= 0 || seq.dt() <= 0 || config == null) {
            return new BakeResult(List.of(), List.of());
        }

        double dtMicros = seq.dt();
        double dtSeconds = dtMicros * 1e-6;
        int totalSteps = seq.totalSteps();
        var clips = seq.clips();

        var tracksById = new HashMap<String, Track>();
        for (var t : seq.tracks()) tracksById.put(t.id(), t);

        int totalChannels = config.totalChannelCount();
        var channelSlots = new SequenceChannel[totalChannels];
        var quadratureSlotIndices = new ArrayList<Integer>();
        int cursor = 0;
        for (var path : config.drivePaths()) {
            int count = path.channelCount();
            for (int sub = 0; sub < count; sub++) {
                channelSlots[cursor + sub] = SequenceChannel.ofPath(path.name(), sub);
                if (path.kind() == AmplitudeKind.QUADRATURE) {
                    quadratureSlotIndices.add(cursor + sub);
                }
            }
            cursor += count;
        }

        var steps = new ArrayList<PulseStep>(totalSteps);
        for (int i = 0; i < totalSteps; i++) {
            double t = i * dtMicros;
            double[] controls = new double[totalChannels];
            for (int k = 0; k < totalChannels; k++) {
                controls[k] = ClipEvaluator.evaluateChannel(clips, tracksById, channelSlots[k], t);
            }
            double rfMagSq = 0;
            for (int qIdx : quadratureSlotIndices) {
                double v = controls[qIdx];
                rfMagSq += v * v;
            }
            double gate = rfMagSq > RF_GATE_THRESHOLD * RF_GATE_THRESHOLD ? 1.0 : 0.0;
            steps.add(new PulseStep(controls, gate));
        }

        int nPulse = 0;
        for (var step : steps) if (step.isRfOn()) nPulse++;
        int nFree = totalSteps - nPulse;

        var segment = new Segment(dtSeconds, nFree, nPulse);
        var pulseSegment = new PulseSegment(List.copyOf(steps));
        return new BakeResult(List.of(segment), List.of(pulseSegment));
    }

    /** Initial human-readable track name for a drive-path channel. */
    public static String defaultTrackName(DrivePath path, int subIndex) {
        if (path.kind() == AmplitudeKind.QUADRATURE) {
            return path.name() + " \u00b7 " + (subIndex == 0 ? "I" : "Q");
        }
        return path.name();
    }

    /**
     * Seed a default track list for a given config: one track per drive-path
     * channel slot, in config order. STATIC paths yield no tracks.
     */
    public static List<Track> defaultTracksFor(SimulationConfig config) {
        if (config == null) return List.of();
        var out = new ArrayList<Track>();
        for (var path : config.drivePaths()) {
            int count = path.channelCount();
            for (int sub = 0; sub < count; sub++) {
                out.add(new Track(SequenceChannel.ofPath(path.name(), sub),
                    defaultTrackName(path, sub)));
            }
        }
        return List.copyOf(out);
    }
}
