package ax.xz.mri.ui.workbench.pane.timeline;

import ax.xz.mri.model.sequence.Track;
import ax.xz.mri.util.MathUtil;

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
    List<Track> tracks,
    Set<String> collapsedTrackIds,
    double viewStart,
    double viewEnd,
    double width,
    double height,
    double labelWidth,
    double rightPad,
    double bottomPad,
    double collapsedTrackHeight
) {
    /** Weight shared by every expanded track lane. */
    public static final double LANE_WEIGHT = 1.0;

    public TimelineGeometry {
        tracks = List.copyOf(tracks);
        collapsedTrackIds = Set.copyOf(collapsedTrackIds);
    }

    public boolean isCollapsed(Track track) {
        return collapsedTrackIds.contains(track.id());
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

    public double pixelSpanToTime(double pixels) {
        double span = viewEnd - viewStart;
        return pixels / plotWidth() * span;
    }

    // ── Track layout ─────────────────────────────────────────────────────────

    public double totalExpandedWeight() {
        double w = 0;
        for (var t : tracks) if (!isCollapsed(t)) w += LANE_WEIGHT;
        return Math.max(0.1, w);
    }

    public double totalCollapsedHeight() {
        double h = 0;
        for (var t : tracks) if (isCollapsed(t)) h += collapsedTrackHeight;
        return h;
    }

    public double expandedAreaHeight() {
        return Math.max(1, plotHeight() - totalCollapsedHeight());
    }

    public double trackTop(int index) {
        double y = plotTop();
        double expanded = expandedAreaHeight();
        double totalWeight = totalExpandedWeight();
        for (int i = 0; i < index && i < tracks.size(); i++) {
            var t = tracks.get(i);
            if (isCollapsed(t)) y += collapsedTrackHeight;
            else y += expanded * LANE_WEIGHT / totalWeight;
        }
        return y;
    }

    public double trackHeight(int index) {
        var t = tracks.get(index);
        if (isCollapsed(t)) return collapsedTrackHeight;
        return expandedAreaHeight() * LANE_WEIGHT / totalExpandedWeight();
    }

    public double trackMid(int index) {
        return trackTop(index) + trackHeight(index) / 2;
    }

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

    public double valueToY(int trackIndex, double value, double max) {
        double top = trackTop(trackIndex);
        double h = trackHeight(trackIndex);
        double mid = top + h / 2;
        double normalised = max > 0 ? MathUtil.clampUnit(value / max) : 0;
        return mid - normalised * (h * 0.42);
    }

    public double yToValue(int trackIndex, double y, double max) {
        double top = trackTop(trackIndex);
        double h = trackHeight(trackIndex);
        double mid = top + h / 2;
        return -(y - mid) / (h * 0.42) * max;
    }
}
