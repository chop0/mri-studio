package ax.xz.mri.model.sequence;

import java.util.List;

/**
 * A clip-based sequence representation.
 *
 * <p>A sequence is the combination of:
 * <ul>
 *   <li><b>Tracks</b> — arrangement lanes; each targets one {@link SequenceChannel}
 *       output defined by the simulation config. Multiple tracks may target
 *       the same output; their clips sum at evaluation time.</li>
 *   <li><b>Clips</b> — waveform-bearing pieces placed on a track (referenced
 *       by {@link SignalClip#trackId()}).</li>
 * </ul>
 *
 * <p>This is the authoritative editing model. On save, it is baked down
 * to {@link Segment}/{@link PulseSegment} arrays for simulator compatibility.
 */
public record ClipSequence(
    double dt,
    double totalDuration,
    List<Track> tracks,
    List<SignalClip> clips
) {
    public ClipSequence {
        tracks = tracks == null ? List.of() : List.copyOf(tracks);
        clips = clips == null ? List.of() : List.copyOf(clips);
    }

    /** Total number of discrete time steps when baked. */
    public int totalSteps() {
        return dt > 0 ? (int) Math.ceil(totalDuration / dt) : 0;
    }
}
