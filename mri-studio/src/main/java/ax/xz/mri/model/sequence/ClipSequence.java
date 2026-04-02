package ax.xz.mri.model.sequence;

import java.util.List;

/**
 * A clip-based sequence representation: a collection of signal clips
 * placed on channels over a total duration with a uniform time step.
 *
 * <p>This is the authoritative editing model. On save, it is baked down
 * to {@link Segment}/{@link PulseSegment} arrays for simulator compatibility.
 */
public record ClipSequence(
    double dt,
    double totalDuration,
    List<SignalClip> clips
) {
    public ClipSequence {
        clips = clips == null ? List.of() : List.copyOf(clips);
    }

    /** Total number of discrete time steps when baked. */
    public int totalSteps() {
        return dt > 0 ? (int) Math.ceil(totalDuration / dt) : 0;
    }
}
