package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.util.MathUtil;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.property.SimpleStringProperty;

/** Cross-section/geometry pane state and cached shading output. */
public class GeometryViewModel {
    private static final double MIN_VISIBLE_SPAN = 2.0;

    public final DoubleProperty halfHeight = new SimpleDoubleProperty(80);
    public final DoubleProperty zCenter = new SimpleDoubleProperty(0);
    public final BooleanProperty showSliceOverlay = new SimpleBooleanProperty(true);
    public final BooleanProperty showLabels = new SimpleBooleanProperty(true);
    public final ObjectProperty<GeometryShadingSnapshot> shadingSnapshot = new SimpleObjectProperty<>();
    public final BooleanProperty shadingComputing = new SimpleBooleanProperty(false);
    public final StringProperty statusMessage = new SimpleStringProperty("");

    public double visibleStart() {
        return zCenter.get() - halfHeight.get();
    }

    public double visibleEnd() {
        return zCenter.get() + halfHeight.get();
    }

    public void setVisibleRange(double start, double end, double domainStart, double domainEnd) {
        double span = MathUtil.clamp(end - start, MIN_VISIBLE_SPAN, Math.max(MIN_VISIBLE_SPAN, domainEnd - domainStart));
        double clampedStart = MathUtil.clamp(start, domainStart, domainEnd - span);
        double clampedEnd = MathUtil.clamp(end, clampedStart + span, domainEnd);
        zCenter.set((clampedStart + clampedEnd) / 2);
        halfHeight.set((clampedEnd - clampedStart) / 2);
        normalizeVisibleRange(domainStart, domainEnd);
    }

    public void fitVisibleRange(double domainStart, double domainEnd) {
        zCenter.set((domainStart + domainEnd) / 2);
        halfHeight.set(Math.max(MIN_VISIBLE_SPAN, domainEnd - domainStart) / 2);
        normalizeVisibleRange(domainStart, domainEnd);
    }

    public void zoomVisibleRangeAround(double anchor, double factor, double domainStart, double domainEnd) {
        double currentSpan = Math.max(MIN_VISIBLE_SPAN, visibleEnd() - visibleStart());
        double nextSpan = MathUtil.clamp(currentSpan * factor, MIN_VISIBLE_SPAN, Math.max(MIN_VISIBLE_SPAN, domainEnd - domainStart));
        double nextStart = anchor - (anchor - visibleStart()) / currentSpan * nextSpan;
        setVisibleRange(nextStart, nextStart + nextSpan, domainStart, domainEnd);
    }

    public void normalizeVisibleRange(double domainStart, double domainEnd) {
        double span = MathUtil.clamp(halfHeight.get() * 2, MIN_VISIBLE_SPAN, Math.max(MIN_VISIBLE_SPAN, domainEnd - domainStart));
        double centre = MathUtil.clamp(zCenter.get(), domainStart + span / 2, domainEnd - span / 2);
        zCenter.set(centre);
        halfHeight.set(span / 2);
    }
}
