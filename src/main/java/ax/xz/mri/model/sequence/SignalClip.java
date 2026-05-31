package ax.xz.mri.model.sequence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * An immutable signal clip placed on a {@link Track} of a pulse sequence.
 *
 * <p>Each clip defines a waveform shape over a time interval; the track it
 * belongs to determines which simulator output channel the shape contributes
 * to. Multiple tracks may route to the same output, and multiple clips on the
 * same track (or on different tracks sharing an output) combine additively at
 * evaluation time.
 *
 * <h3>Premiere-style trimming</h3>
 * <p>The waveform is defined over the clip's full <em>media extent</em>
 * ({@code mediaDuration} μs). The visible portion on the timeline starts at
 * {@code mediaOffset} into this media and lasts for {@code duration}. Trimming
 * the left edge increases {@code mediaOffset} and moves {@code startTime}
 * right; trimming the right edge only decreases {@code duration}. Dragging an
 * edge back out reveals more of the original waveform without changing its
 * shape — this is {@link #centred()}'s inverse: the media extent sits around
 * the visible window with room either side.
 *
 * <h3>Stay-centred edges</h3>
 * <p>When {@link #stayCentred} is {@code true} (the default for new clips),
 * resizing one edge mirrors the change to the opposite edge so the visible
 * window's centre stays put. The {@code edit/} package's {@code ClipEditor}
 * implements that symmetry; this record only holds the flag.
 *
 * <h3>Shape parameters</h3>
 * <p>Each {@link ClipShape} variant owns its own typed parameters; there is no
 * shared parameter map. Changing shape is a full replacement
 * ({@link #withShape(ClipShape)}) — parameters are carried over only when they
 * still make sense, via caller logic.
 */
public record SignalClip(
    String id,
    String trackId,
    ClipShape shape,
    double startTime,
    double duration,
    double amplitude,
    double mediaOffset,
    double mediaDuration,
    boolean stayCentred
) {
    public SignalClip {
        if (id == null) id = UUID.randomUUID().toString();
        if (trackId == null) throw new IllegalArgumentException("SignalClip.trackId must be non-null");
        if (mediaDuration <= 0) mediaDuration = duration;
    }

    /**
     * Jackson factory: project files written before {@code stayCentred} existed
     * deserialise with {@code stayCentred = true} (matching the new-clip
     * default), rather than the boolean-primitive default of {@code false}.
     */
    @JsonCreator
    public static SignalClip fromJson(
        @JsonProperty("id") String id,
        @JsonProperty("trackId") String trackId,
        @JsonProperty("shape") ClipShape shape,
        @JsonProperty("startTime") double startTime,
        @JsonProperty("duration") double duration,
        @JsonProperty("amplitude") double amplitude,
        @JsonProperty("mediaOffset") double mediaOffset,
        @JsonProperty("mediaDuration") double mediaDuration,
        @JsonProperty("stayCentred") Boolean stayCentred
    ) {
        return new SignalClip(id, trackId, shape, startTime, duration, amplitude,
            mediaOffset, mediaDuration, stayCentred == null || stayCentred);
    }

    /** End time in microseconds on the timeline. */
    public double endTime() { return startTime + duration; }

    /** Centre of the visible window, in microseconds. */
    public double centre() { return startTime + duration * 0.5; }

    /**
     * Normalised position within the media extent at a given absolute time —
     * returns a value in {@code [0, 1]} for times inside the visible window.
     * Returns 0 for a zero-length media extent.
     */
    public double mediaU(double absoluteTime) {
        if (mediaDuration <= 0) return 0;
        return (mediaOffset + (absoluteTime - startTime)) / mediaDuration;
    }

    /**
     * Evaluate the signal value at an absolute time. Returns 0 outside the
     * visible time range {@code [startTime, endTime)}.
     */
    public double evaluate(double absoluteTime) {
        if (absoluteTime < startTime || absoluteTime >= endTime() || duration <= 0) return 0;
        return amplitude * shape.evaluate(mediaU(absoluteTime), mediaDuration);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Factories
    // ══════════════════════════════════════════════════════════════════════════

    /** Factor applied to a visible duration to compute the surrounding media extent. */
    public static final double MEDIA_EXTENT_FACTOR = 4.0;
    /** Offset of the visible window within that extent (fraction of visible duration). */
    public static final double MEDIA_OFFSET_FACTOR = 1.5;

    /**
     * Build a fresh clip with a media extent centred around the visible window
     * (four times the duration, shifted so the visible part sits in the middle).
     * The shape's own default parameters are used; {@code stayCentred} starts
     * {@code true}.
     */
    public static SignalClip freshCentred(String trackId, ClipKind kind,
                                          double startTime, double duration, double amplitude) {
        double mediaDuration = duration * MEDIA_EXTENT_FACTOR;
        double mediaOffset = duration * MEDIA_OFFSET_FACTOR;
        return new SignalClip(
            null, trackId, kind.defaultFor(mediaDuration),
            startTime, duration, amplitude,
            mediaOffset, mediaDuration, true
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // In-place transformations (return a new instance)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Re-centre this clip so the visible window sits in the middle of a fresh
     * {@link #MEDIA_EXTENT_FACTOR}×-duration media extent — gives the user
     * room to drag either trim edge back out. Sets {@code stayCentred = true}.
     */
    public SignalClip centred() {
        double newMediaDuration = duration * MEDIA_EXTENT_FACTOR;
        double newMediaOffset = duration * MEDIA_OFFSET_FACTOR;
        return new SignalClip(id, trackId, shape,
            startTime, duration, amplitude,
            newMediaOffset, newMediaDuration, true);
    }

    /**
     * Split this clip into two at an absolute timeline position. Both halves
     * preserve the original shape identity; shape-specific parameters are
     * re-fitted via {@link ClipShape#onSplit(double, double, boolean)} so each
     * half keeps its original behaviour inside its new window.
     */
    public Split split(double splitTime) {
        if (splitTime <= startTime || splitTime >= endTime())
            throw new IllegalArgumentException("splitTime must be strictly inside the clip's visible range");

        double leftDuration = splitTime - startTime;
        double rightDuration = endTime() - splitTime;
        double splitU = leftDuration / duration;

        var left = new SignalClip(
            UUID.randomUUID().toString(), trackId, shape.onSplit(duration, splitU, true),
            startTime, leftDuration, amplitude,
            0, leftDuration, stayCentred
        );
        var right = new SignalClip(
            UUID.randomUUID().toString(), trackId, shape.onSplit(duration, splitU, false),
            splitTime, rightDuration, amplitude,
            0, rightDuration, stayCentred
        );
        return new Split(left, right);
    }

    /** Result of {@link #split(double)}. */
    public record Split(SignalClip left, SignalClip right) {}

    // ── with* mutators ───────────────────────────────────────────────────────

    public SignalClip withStartTime(double newStartTime) {
        return new SignalClip(id, trackId, shape, newStartTime, duration, amplitude, mediaOffset, mediaDuration, stayCentred);
    }

    public SignalClip withDuration(double newDuration) {
        return new SignalClip(id, trackId, shape, startTime, newDuration, amplitude, mediaOffset, mediaDuration, stayCentred);
    }

    public SignalClip withAmplitude(double newAmplitude) {
        return new SignalClip(id, trackId, shape, startTime, duration, newAmplitude, mediaOffset, mediaDuration, stayCentred);
    }

    public SignalClip withMediaDuration(double newMediaDuration) {
        return new SignalClip(id, trackId, shape, startTime, duration, amplitude, mediaOffset, newMediaDuration, stayCentred);
    }

    public SignalClip withShape(ClipShape newShape) {
        return new SignalClip(id, trackId, newShape, startTime, duration, amplitude, mediaOffset, mediaDuration, stayCentred);
    }

    public SignalClip withTrack(String newTrackId) {
        return new SignalClip(id, newTrackId, shape, startTime, duration, amplitude, mediaOffset, mediaDuration, stayCentred);
    }

    public SignalClip withStayCentred(boolean newStayCentred) {
        return new SignalClip(id, trackId, shape, startTime, duration, amplitude, mediaOffset, mediaDuration, newStayCentred);
    }

    public SignalClip withNewId() {
        return new SignalClip(UUID.randomUUID().toString(), trackId, shape,
            startTime, duration, amplitude, mediaOffset, mediaDuration, stayCentred);
    }
}
