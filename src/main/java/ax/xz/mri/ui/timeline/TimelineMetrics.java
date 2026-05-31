package ax.xz.mri.ui.timeline;

import ax.xz.mri.ui.time.TimeAxis;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.value.ObservableDoubleValue;
import javafx.beans.value.ObservableValue;

/**
 * The shared pixel↔time conversion for the timeline editor.
 *
 * <p>One {@link TimelineMetrics} instance per {@code TimelineRoot}. Children
 * (clips, track lanes, cursor overlay, snap chip, etc.) read {@link #pxPerMicro}
 * to position themselves; they never compute their own conversion. Putting the
 * conversion behind a {@link DoubleBinding} means every node's layout is
 * automatically invalidated when the viewport pans/zooms or the parent
 * resizes — no manual {@code requestLayout()} cascades.
 */
public final class TimelineMetrics {
    public final TimeAxis timeAxis;
    /** Width of the lane area in pixels — bound to the parent's content width. */
    public final ObservableDoubleValue laneWidth;
    /** Conversion factor: pixels per microsecond, derived from laneWidth and viewport span. */
    public final DoubleBinding pxPerMicro;

    public TimelineMetrics(TimeAxis timeAxis, ObservableDoubleValue laneWidth) {
        this.timeAxis = timeAxis;
        this.laneWidth = laneWidth;
        this.pxPerMicro = Bindings.createDoubleBinding(() -> {
            double span = timeAxis.viewport.span();
            double w = laneWidth.get();
            return span <= 0 ? 0 : w / span;
        }, laneWidth, timeAxis.viewport.start, timeAxis.viewport.end);
    }

    /** Time at a pixel offset from the lane's left edge. */
    public double xToTime(double x) {
        double k = pxPerMicro.get();
        return k <= 0 ? timeAxis.viewport.start.get() : timeAxis.viewport.start.get() + x / k;
    }

    /** Pixel offset (from the lane's left edge) for an absolute time. */
    public double timeToX(double micros) {
        return (micros - timeAxis.viewport.start.get()) * pxPerMicro.get();
    }

    /**
     * Build a binding that tracks the X position (relative to the lane's left
     * edge) of an arbitrary time-valued {@link ObservableValue}. Used by
     * cursor overlays and snap chips to track moving times reactively.
     */
    public DoubleBinding xOf(ObservableValue<? extends Number> micros) {
        return Bindings.createDoubleBinding(
            () -> (micros.getValue().doubleValue() - timeAxis.viewport.start.get()) * pxPerMicro.get(),
            micros, timeAxis.viewport.start, timeAxis.viewport.end, laneWidth);
    }
}
