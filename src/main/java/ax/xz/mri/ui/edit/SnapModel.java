package ax.xz.mri.ui.edit;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;

import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Layout-snap settings for the timeline editor.
 *
 * <p>Governs <em>clip layout</em> only — moving and resizing clip edges. The
 * playhead cursor scrubs without snapping unless the user opts in (Shift). The
 * threshold is computed as a fraction of the visible viewport span, so snap
 * targets feel the same density at every zoom level.
 *
 * <p>The snap candidates are: every clip's start and end time, plus an
 * optional uniform grid stride. The closest candidate within the threshold
 * wins; otherwise the raw input is returned untouched.
 */
public final class SnapModel {
    private static final double THRESHOLD_FRACTION = 0.005;

    public final BooleanProperty enabled = new SimpleBooleanProperty(true);
    public final DoubleProperty gridSize = new SimpleDoubleProperty(0);

    private final Supplier<Iterable<Double>> clipEdges;
    private final DoubleSupplier viewportSpan;

    public SnapModel(Supplier<Iterable<Double>> clipEdges, DoubleSupplier viewportSpan) {
        this.clipEdges = clipEdges;
        this.viewportSpan = viewportSpan;
    }

    /**
     * Snap a raw time to the nearest clip edge or grid step within the
     * threshold. Returns the raw input unchanged when snap is off, no
     * candidate is close enough, or the threshold computes to zero.
     */
    public double snapTime(double rawTime) {
        if (!enabled.get()) return rawTime;
        double threshold = viewportSpan.getAsDouble() * THRESHOLD_FRACTION;
        if (threshold <= 0) return rawTime;

        double bestSnap = rawTime;
        double bestDist = threshold;
        for (var edge : clipEdges.get()) {
            double d = Math.abs(rawTime - edge);
            if (d < bestDist) { bestDist = d; bestSnap = edge; }
        }
        if (bestDist < threshold) return bestSnap;

        double grid = gridSize.get();
        if (grid > 0) {
            double snapped = Math.round(rawTime / grid) * grid;
            if (Math.abs(snapped - rawTime) < threshold) return snapped;
        }
        return rawTime;
    }
}
