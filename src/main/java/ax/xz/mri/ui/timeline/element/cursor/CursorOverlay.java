package ax.xz.mri.ui.timeline.element.cursor;

import ax.xz.mri.ui.edit.EditPreview;
import ax.xz.mri.ui.edit.EditSession;
import ax.xz.mri.ui.timeline.TimelineMetrics;
import javafx.beans.binding.Bindings;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Line;

/**
 * A vertical line bound to the cursor's time, drawn over the entire lane
 * stack. The line's X position is a {@link Bindings} expression of
 * {@link TimelineMetrics#pxPerMicro} and the cursor — pure reactive layout,
 * no manual repaints.
 *
 * <p>Dims itself when an edit gesture is in progress so the cursor doesn't
 * compete with snap-line and clip-edge feedback during a drag.
 */
public final class CursorOverlay extends Pane {
    private final Line line = new Line();

    public CursorOverlay(EditSession session, TimelineMetrics metrics) {
        getStyleClass().add("cursor-overlay");
        setMouseTransparent(true);
        setPickOnBounds(false);

        // Clip overflow — when the cursor is outside the viewport, the line's
        // X position is negative or > width; without this clip the line paints
        // outside the lane area into the surrounding chrome.
        var clipRect = new javafx.scene.shape.Rectangle();
        clipRect.widthProperty().bind(widthProperty());
        clipRect.heightProperty().bind(heightProperty());
        setClip(clipRect);

        line.getStyleClass().add("cursor-line");
        line.startXProperty().bind(metrics.xOf(metrics.timeAxis.cursor.time));
        line.endXProperty().bind(metrics.xOf(metrics.timeAxis.cursor.time));
        line.setStartY(0);
        line.endYProperty().bind(heightProperty());
        line.opacityProperty().bind(Bindings.createDoubleBinding(
            () -> session.preview.active.get() == null ? 0.85 : 0.35,
            session.preview.active));
        getChildren().add(line);
    }

    // Don't let the cursor line's bound startX drive our pref size — it
    // could be arbitrarily large or negative, and that would propagate up
    // the layout tree and resize the shell on every cursor move.
    @Override protected double computePrefWidth(double h)  { return 0; }
    @Override protected double computePrefHeight(double w) { return 0; }
    @Override protected double computeMinWidth(double h)   { return 0; }
    @Override protected double computeMinHeight(double w)  { return 0; }
}
