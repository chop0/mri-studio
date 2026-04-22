package ax.xz.mri.ui.workbench.pane.timeline;

import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.ui.viewmodel.EditorTrack;

import java.util.List;
import java.util.Set;

/**
 * Pure geometry for the timeline canvas — a snapshot of everything needed to
 * map between time/amplitude and pixel space.
 *
 * <p>Every rendering and hit-test calculation goes through this record, so
 * there's exactly one copy of the maths. The canvas constructs a new
 * {@code TimelineGeometry} each time it paints (or answers a hit-test query);
 * keeping it immutable and allocation-cheap means there's no mutable shared
 * state between paint and interaction code.
 */
public record TimelineGeometry(
    List<EditorTrack> tracks,
    Set<SequenceChannel> collapsedChannels,
    double viewStart,
    double viewEnd,
    double width,
    double height,
    double labelWidth,
    double rightPad,
    double bottomPad,
    double collapsedTrackHeight
) {
    /** Weight of the single RF-gate lane relative to a full track. */
    public static final double GATE_WEIGHT = 0.55;
    /** Weight of a normal field lane. */
    public static final double LANE_WEIGHT = 1.0;

    public TimelineGeometry {
        tracks = List.copyOf(tracks);
        collapsedChannels = Set.copyOf(collapsedChannels);
    }

    public boolean isCollapsed(EditorTrack track) {
        return collapsedChannels.contains(track.channel());
    }

    // ── Plot rectangle ───────────────────────────────────────────────────────

    public double plotLeft()   { return labelWidth; }
    public double plotRight()  { return width - rightPad; }
    public double plotTop()    { return 0; }
    public double plotBottom() { return height - bottomPad; }
    public double plotWidth()  { return Math.max(1, plotRight() - plotLeft()); }
    public double plotHeight() { return Math.max(1, plotBottom() - plotTop()); }

    // ── Time ↔ X ─────────────────────────────────────────────────────────────

    public double timeToX(double timeMicros) {
        double span = Math.max(1e-9, viewEnd - viewStart);
        return plotLeft() + (timeMicros - viewStart) / span * plotWidth();
    }

    public double xToTime(double x) {
        double span = Math.max(1e-9, viewEnd - viewStart);
        return viewStart + (x - plotLeft()) / plotWidth() * span;
    }

    /** Span between two pixel positions expressed as a time delta. */
    public double pixelSpanToTime(double pixels) {
        double span = viewEnd - viewStart;
        return pixels / plotWidth() * span;
    }

    // ── Track layout ─────────────────────────────────────────────────────────

    /** Weight of one track in the expanded-area layout. Collapsed tracks use a fixed height. */
    public double trackWeight(EditorTrack track) {
        if (isCollapsed(track)) return 0;
        return track.isGate() ? GATE_WEIGHT : LANE_WEIGHT;
    }

    /** Sum of weights of all expanded tracks. */
    public double totalExpandedWeight() {
        double w = 0;
        for (var t : tracks) if (!isCollapsed(t)) w += trackWeight(t);
        return Math.max(0.1, w);
    }

    /** Total pixels consumed by collapsed tracks. */
    public double totalCollapsedHeight() {
        double h = 0;
        for (var t : tracks) if (isCollapsed(t)) h += collapsedTrackHeight;
        return h;
    }

    /** Pixels available for expanded tracks after accounting for collapsed bars. */
    public double expandedAreaHeight() {
        return Math.max(1, plotHeight() - totalCollapsedHeight());
    }

    /** Top edge of a track by index (0 = topmost). */
    public double trackTop(int index) {
        double y = plotTop();
        double expanded = expandedAreaHeight();
        double totalWeight = totalExpandedWeight();
        for (int i = 0; i < index && i < tracks.size(); i++) {
            var t = tracks.get(i);
            if (isCollapsed(t)) y += collapsedTrackHeight;
            else y += expanded * trackWeight(t) / totalWeight;
        }
        return y;
    }

    /** Height of a track by index. */
    public double trackHeight(int index) {
        var t = tracks.get(index);
        if (isCollapsed(t)) return collapsedTrackHeight;
        return expandedAreaHeight() * trackWeight(t) / totalExpandedWeight();
    }

    /** Mid-line of a track by index. */
    public double trackMid(int index) {
        return trackTop(index) + trackHeight(index) / 2;
    }

    /** Index of the track at a given y coordinate, or {@code -1} if outside. */
    public int trackAtY(double y) {
        double cum = plotTop();
        for (int i = 0; i < tracks.size(); i++) {
            double h = trackHeight(i);
            if (y >= cum && y < cum + h) return i;
            cum += h;
        }
        return -1;
    }

    // ── Amplitude ↔ Y (per-track) ───────────────────────────────────────────

    /**
     * Convert a signal value to a pixel y inside a given track, using the
     * provided half-span {@code max} (the axis shows [-max, +max]).
     */
    public double valueToY(int trackIndex, double value, double max) {
        double top = trackTop(trackIndex);
        double h = trackHeight(trackIndex);
        double mid = top + h / 2;
        double normalised = max > 0 ? Math.max(-1, Math.min(1, value / max)) : 0;
        // 0.42 keeps a 8 % margin top and bottom so the peak isn't clipped.
        return mid - normalised * (h * 0.42);
    }

    public double yToValue(int trackIndex, double y, double max) {
        double top = trackTop(trackIndex);
        double h = trackHeight(trackIndex);
        double mid = top + h / 2;
        return -(y - mid) / (h * 0.42) * max;
    }
}
