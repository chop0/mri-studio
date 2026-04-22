package ax.xz.mri.model.sequence;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * An immutable signal clip placed on one channel of a pulse sequence.
 *
 * <p>Each clip defines a waveform shape over a time interval on a specific
 * channel; clips on the same channel with overlapping time ranges combine
 * additively at evaluation time.
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
 * <h3>Shape parameters</h3>
 * <p>Each {@link ClipShape} variant owns its own typed parameters; there is no
 * shared parameter map. Changing shape is a full replacement
 * ({@link #withShape(ClipShape)}) — parameters are carried over only when they
 * still make sense, via caller logic.
 */
public record SignalClip(
    String id,
    SequenceChannel channel,
    ClipShape shape,
    @JsonProperty("start_time") double startTime,
    double duration,
    double amplitude,
    @JsonProperty("media_offset") double mediaOffset,
    @JsonProperty("media_duration") double mediaDuration
) {
    public SignalClip {
        if (id == null) id = UUID.randomUUID().toString();
        if (mediaDuration <= 0) mediaDuration = duration;
    }

    /** End time in microseconds on the timeline. */
    public double endTime() { return startTime + duration; }

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
     * The shape's own default parameters are used.
     */
    public static SignalClip freshCentred(SequenceChannel channel, ClipKind kind,
                                          double startTime, double duration, double amplitude) {
        double mediaDuration = duration * MEDIA_EXTENT_FACTOR;
        double mediaOffset = duration * MEDIA_OFFSET_FACTOR;
        return new SignalClip(
            null, channel, kind.defaultFor(mediaDuration),
            startTime, duration, amplitude,
            mediaOffset, mediaDuration
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // In-place transformations (return a new instance)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Re-centre this clip so the visible window sits in the middle of a fresh
     * {@link #MEDIA_EXTENT_FACTOR}×-duration media extent — gives the user
     * room to drag either trim edge back out.
     */
    public SignalClip centred() {
        double newMediaDuration = duration * MEDIA_EXTENT_FACTOR;
        double newMediaOffset = duration * MEDIA_OFFSET_FACTOR;
        return new SignalClip(id, channel, shape,
            startTime, duration, amplitude,
            newMediaOffset, newMediaDuration);
    }

    /**
     * Split this clip into two at an absolute timeline position. Both halves
     * preserve the original shape identity; shape-specific parameters are
     * re-fitted via {@link ClipShape#onSplit(double, double, boolean)} so each
     * half keeps its original behaviour inside its new window.
     *
     * @return left and right halves. Neither is added to any sequence — that's
     *         the caller's job.
     * @throws IllegalArgumentException if {@code splitTime} is outside the
     *         clip's visible range
     */
    public Split split(double splitTime) {
        if (splitTime <= startTime || splitTime >= endTime())
            throw new IllegalArgumentException("splitTime must be strictly inside the clip's visible range");

        double leftDuration = splitTime - startTime;
        double rightDuration = endTime() - splitTime;
        double splitU = leftDuration / duration;

        var left = new SignalClip(
            UUID.randomUUID().toString(), channel, shape.onSplit(duration, splitU, true),
            startTime, leftDuration, amplitude,
            0, leftDuration
        );
        var right = new SignalClip(
            UUID.randomUUID().toString(), channel, shape.onSplit(duration, splitU, false),
            splitTime, rightDuration, amplitude,
            0, rightDuration
        );
        return new Split(left, right);
    }

    /** Result of {@link #split(double)}. */
    public record Split(SignalClip left, SignalClip right) {}

    // ── with* mutators ───────────────────────────────────────────────────────

    public SignalClip withStartTime(double newStartTime) {
        return new SignalClip(id, channel, shape, newStartTime, duration, amplitude, mediaOffset, mediaDuration);
    }

    public SignalClip withDuration(double newDuration) {
        return new SignalClip(id, channel, shape, startTime, newDuration, amplitude, mediaOffset, mediaDuration);
    }

    public SignalClip withAmplitude(double newAmplitude) {
        return new SignalClip(id, channel, shape, startTime, duration, newAmplitude, mediaOffset, mediaDuration);
    }

    public SignalClip withMediaOffset(double newMediaOffset) {
        return new SignalClip(id, channel, shape, startTime, duration, amplitude, newMediaOffset, mediaDuration);
    }

    public SignalClip withMediaDuration(double newMediaDuration) {
        return new SignalClip(id, channel, shape, startTime, duration, amplitude, mediaOffset, newMediaDuration);
    }

    public SignalClip withShape(ClipShape newShape) {
        return new SignalClip(id, channel, newShape, startTime, duration, amplitude, mediaOffset, mediaDuration);
    }

    public SignalClip withChannel(SequenceChannel newChannel) {
        return new SignalClip(id, newChannel, shape, startTime, duration, amplitude, mediaOffset, mediaDuration);
    }

    public SignalClip withNewId() {
        return new SignalClip(UUID.randomUUID().toString(), channel, shape,
            startTime, duration, amplitude, mediaOffset, mediaDuration);
    }
}
