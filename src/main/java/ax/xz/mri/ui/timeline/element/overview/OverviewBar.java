package ax.xz.mri.ui.timeline.element.overview;

import ax.xz.mri.ui.theme.ThemeTokens;
import ax.xz.mri.ui.time.TimeAxis;
import ax.xz.mri.util.MathUtil;
import javafx.beans.value.ChangeListener;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;

/**
 * Bottom-of-editor strip showing the entire project timeline at a single
 * glance, with a draggable viewport-rectangle overlay.
 *
 * <p>Replaces the previous {@code AxisScrubBar} (382 lines of canvas-rendered
 * scrub semantics) with a thin {@link Pane} that composes:
 * <ul>
 *   <li>a {@link Canvas} background painting tick marks across the full domain;</li>
 *   <li>a {@link Rectangle} for the viewport extent — drag the body to pan,
 *       drag the edges to zoom, click outside to recenter;</li>
 *   <li>a {@link Line} marking the cursor's time position.</li>
 * </ul>
 *
 * <p>The viewport rectangle's X position and width are bound to the time
 * axis: changes from any source (toolbar zoom button, scroll wheel, the
 * top-of-editor RangeSlider) are reflected here automatically.
 */
public final class OverviewBar extends Pane {
    private static final double EDGE_HANDLE_PX = 4;
    private static final double HEIGHT = 32;

    private final TimeAxis timeAxis;
    private final Canvas backdrop = new Canvas();
    private final Rectangle viewportRect = new Rectangle();
    private final Line cursorMark = new Line();
    private final ChangeListener<Number> repaintListener = (obs, o, n) -> repaintAll();

    private Drag drag;

    public OverviewBar(TimeAxis timeAxis) {
        this.timeAxis = timeAxis;
        getStyleClass().add("overview-bar");
        setMinHeight(HEIGHT);
        setPrefHeight(HEIGHT);
        setMaxHeight(HEIGHT);

        backdrop.setMouseTransparent(true);
        viewportRect.getStyleClass().add("overview-viewport");
        cursorMark.getStyleClass().add("overview-cursor");
        cursorMark.setMouseTransparent(true);

        getChildren().addAll(backdrop, viewportRect, cursorMark);

        widthProperty().addListener((obs, o, n) -> repaintAll());
        heightProperty().addListener((obs, o, n) -> repaintAll());
        timeAxis.viewport.start.addListener(repaintListener);
        timeAxis.viewport.end.addListener(repaintListener);
        timeAxis.domain.maxTime.addListener(repaintListener);
        timeAxis.cursor.time.addListener(repaintListener);

        wireMouseHandlers();
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        backdrop.setWidth(getWidth());
        backdrop.setHeight(getHeight());
        repaintAll();
    }

    private void repaintAll() {
        repaintBackdrop();
        repositionViewportRect();
        repositionCursor();
    }

    private void repaintBackdrop() {
        var g = backdrop.getGraphicsContext2D();
        double w = backdrop.getWidth();
        double h = backdrop.getHeight();
        g.clearRect(0, 0, w, h);
        if (w <= 0 || h <= 0) return;

        // Background fill tinted via CSS would be nicer, but Canvas has no CSS
        // hook — paint a flat tint here.
        g.setFill(ThemeTokens.Tone.SURFACE_MUTED);
        g.fillRect(0, 0, w, h);

        // Coarse decade-based tick marks across the full domain.
        double max = Math.max(1, timeAxis.domain.maxTime.get());
        double step = niceStep(max / Math.max(4, w / 60));
        g.setStroke(ThemeTokens.Tone.BORDER_SUBTLE);
        g.setLineWidth(ThemeTokens.Stroke.HAIRLINE);
        for (double t = 0; t <= max; t += step) {
            double x = t / max * w;
            g.strokeLine(x, 0, x, h);
        }

        // Outer border separating the strip from the editor lanes above.
        g.setStroke(ThemeTokens.Tone.BORDER);
        g.strokeLine(0, 0.5, w, 0.5);
        g.strokeLine(0, h - 0.5, w, h - 0.5);
    }

    private void repositionViewportRect() {
        double w = getWidth();
        double h = getHeight();
        double max = Math.max(1, timeAxis.domain.maxTime.get());
        double vS = timeAxis.viewport.start.get();
        double vE = timeAxis.viewport.end.get();
        viewportRect.setX(vS / max * w);
        viewportRect.setY(2);
        viewportRect.setWidth(Math.max(2, (vE - vS) / max * w));
        viewportRect.setHeight(Math.max(0, h - 4));
    }

    private void repositionCursor() {
        double w = getWidth();
        double h = getHeight();
        double max = Math.max(1, timeAxis.domain.maxTime.get());
        double x = timeAxis.cursor.time.get() / max * w;
        cursorMark.setStartX(x);
        cursorMark.setEndX(x);
        cursorMark.setStartY(0);
        cursorMark.setEndY(h);
    }

    // ── Mouse: drag viewport rect to pan, drag edges to zoom ─────────────────

    private void wireMouseHandlers() {
        setOnMouseMoved(e -> setCursor(cursorFor(e.getX())));

        setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            double x = e.getX();
            double rectStart = viewportRect.getX();
            double rectEnd   = rectStart + viewportRect.getWidth();
            if (Math.abs(x - rectStart) <= EDGE_HANDLE_PX) {
                drag = new Drag(DragKind.RESIZE_LEFT, x, rectStart, rectEnd);
                setCursor(Cursor.W_RESIZE);
            } else if (Math.abs(x - rectEnd) <= EDGE_HANDLE_PX) {
                drag = new Drag(DragKind.RESIZE_RIGHT, x, rectStart, rectEnd);
                setCursor(Cursor.E_RESIZE);
            } else if (x > rectStart && x < rectEnd) {
                drag = new Drag(DragKind.PAN, x, rectStart, rectEnd);
                setCursor(Cursor.CLOSED_HAND);
            } else {
                // Click outside the rect: snap viewport so its centre is here.
                double t = timeFromX(x);
                double span = timeAxis.viewport.span();
                double newStart = MathUtil.clamp(t - span / 2, 0,
                    Math.max(0, timeAxis.domain.maxTime.get() - span));
                timeAxis.viewport.setSpan(newStart, newStart + span);
            }
            e.consume();
        });

        setOnMouseDragged(e -> {
            if (drag == null) return;
            double dx = e.getX() - drag.startX();
            switch (drag.kind()) {
                case PAN -> {
                    double startX = drag.rectStart() + dx;
                    double widthPx = drag.rectEnd() - drag.rectStart();
                    double newStart = clampPan(startX);
                    double newEnd = newStart + widthPx;
                    timeAxis.viewport.setSpan(timeFromX(newStart), timeFromX(newEnd));
                }
                case RESIZE_LEFT -> {
                    double newStartX = MathUtil.clamp(drag.rectStart() + dx, 0, drag.rectEnd() - 4);
                    timeAxis.viewport.setSpan(timeFromX(newStartX), timeFromX(drag.rectEnd()));
                }
                case RESIZE_RIGHT -> {
                    double newEndX = MathUtil.clamp(drag.rectEnd() + dx, drag.rectStart() + 4, getWidth());
                    timeAxis.viewport.setSpan(timeFromX(drag.rectStart()), timeFromX(newEndX));
                }
            }
            e.consume();
        });

        setOnMouseReleased(e -> {
            drag = null;
            setCursor(cursorFor(e.getX()));
            e.consume();
        });
    }

    private Cursor cursorFor(double x) {
        double rectStart = viewportRect.getX();
        double rectEnd   = rectStart + viewportRect.getWidth();
        if (Math.abs(x - rectStart) <= EDGE_HANDLE_PX) return Cursor.W_RESIZE;
        if (Math.abs(x - rectEnd)   <= EDGE_HANDLE_PX) return Cursor.E_RESIZE;
        if (x > rectStart && x < rectEnd) return Cursor.OPEN_HAND;
        return Cursor.DEFAULT;
    }

    private double timeFromX(double x) {
        double w = Math.max(1, getWidth());
        return x / w * Math.max(1, timeAxis.domain.maxTime.get());
    }

    private double clampPan(double startX) {
        double widthPx = viewportRect.getWidth();
        return MathUtil.clamp(startX, 0, getWidth() - widthPx);
    }

    private static double niceStep(double raw) {
        if (raw <= 0) return 1;
        double exp = Math.pow(10, Math.floor(Math.log10(raw)));
        double mantissa = raw / exp;
        double nice =
            mantissa < 1.5 ? 1
          : mantissa < 3.5 ? 2
          : mantissa < 7.5 ? 5
          : 10;
        return nice * exp;
    }

    private enum DragKind { PAN, RESIZE_LEFT, RESIZE_RIGHT }
    private record Drag(DragKind kind, double startX, double rectStart, double rectEnd) {}
}
