package ax.xz.mri.model.sequence;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An immutable signal clip placed on one channel of a pulse sequence.
 *
 * <p>Each clip defines a waveform shape over a time interval on a specific channel.
 * Clips on the same channel with overlapping time ranges combine additively.
 *
 * <h3>Premiere-style trimming</h3>
 * <p>The clip's waveform is defined over its full <em>media extent</em>
 * ({@code mediaDuration} μs). The visible portion on the timeline starts at
 * {@code mediaOffset} into this media and lasts for {@code duration}. Trimming the
 * left edge increases {@code mediaOffset} and moves {@code startTime} right;
 * trimming the right edge only decreases {@code duration}. Dragging an edge back
 * out reveals more of the original waveform without changing its shape.
 *
 * <p>Shape-specific parameters live in {@code params}:
 * <ul>
 *   <li>SINC: {@code bandwidthHz}, {@code centerOffset}, {@code windowFactor}
 *   <li>TRAPEZOID: {@code riseTime} (μs), {@code flatTime} (μs)
 *   <li>GAUSSIAN: {@code sigma} (μs)
 *   <li>TRIANGLE: {@code peakPosition} (0–1)
 *   <li>SPLINE: uses {@code splinePoints}
 *   <li>CONSTANT: no extra params
 * </ul>
 */
public record SignalClip(
    String id,
    SequenceChannel channel,
    ClipShape shape,
    @JsonProperty("start_time") double startTime,
    double duration,
    double amplitude,
    @JsonProperty("media_offset") double mediaOffset,
    @JsonProperty("media_duration") double mediaDuration,
    Map<String, Double> params,
    @JsonProperty("spline_points") List<SplinePoint> splinePoints
) {
    /** A control point for spline clips, with t normalised to [0,1] within the media extent. */
    public record SplinePoint(double t, double value) {}

    public SignalClip {
        if (id == null) id = UUID.randomUUID().toString();
        params = params == null ? Map.of() : Map.copyOf(params);
        splinePoints = splinePoints == null ? List.of() : List.copyOf(splinePoints);
        if (mediaDuration <= 0) mediaDuration = duration;
    }

    /** End time in microseconds on the timeline. */
    public double endTime() {
        return startTime + duration;
    }

    /** The normalised position within the media at a given absolute time. Returns [0,1] range. */
    public double mediaU(double absoluteTime) {
        if (mediaDuration <= 0) return 0;
        return (mediaOffset + (absoluteTime - startTime)) / mediaDuration;
    }

    /** Get a shape parameter with a default value. */
    public double param(String key, double defaultValue) {
        return params.getOrDefault(key, defaultValue);
    }

    public SignalClip withStartTime(double newStartTime) {
        return new SignalClip(id, channel, shape, newStartTime, duration, amplitude, mediaOffset, mediaDuration, params, splinePoints);
    }

    public SignalClip withDuration(double newDuration) {
        return new SignalClip(id, channel, shape, startTime, newDuration, amplitude, mediaOffset, mediaDuration, params, splinePoints);
    }

    public SignalClip withAmplitude(double newAmplitude) {
        return new SignalClip(id, channel, shape, startTime, duration, newAmplitude, mediaOffset, mediaDuration, params, splinePoints);
    }

    public SignalClip withMediaOffset(double newMediaOffset) {
        return new SignalClip(id, channel, shape, startTime, duration, amplitude, newMediaOffset, mediaDuration, params, splinePoints);
    }

    public SignalClip withMediaDuration(double newMediaDuration) {
        return new SignalClip(id, channel, shape, startTime, duration, amplitude, mediaOffset, newMediaDuration, params, splinePoints);
    }

    public SignalClip withParams(Map<String, Double> newParams) {
        return new SignalClip(id, channel, shape, startTime, duration, amplitude, mediaOffset, mediaDuration, newParams, splinePoints);
    }

    public SignalClip withSplinePoints(List<SplinePoint> newPoints) {
        return new SignalClip(id, channel, shape, startTime, duration, amplitude, mediaOffset, mediaDuration, params, newPoints);
    }

    public SignalClip withNewId() {
        return new SignalClip(UUID.randomUUID().toString(), channel, shape, startTime, duration, amplitude, mediaOffset, mediaDuration, params, splinePoints);
    }

    public SignalClip withShape(ClipShape newShape) {
        return new SignalClip(id, channel, newShape, startTime, duration, amplitude, mediaOffset, mediaDuration, params, splinePoints);
    }

    public SignalClip withChannel(SequenceChannel newChannel) {
        return new SignalClip(id, newChannel, shape, startTime, duration, amplitude, mediaOffset, mediaDuration, params, splinePoints);
    }
}
