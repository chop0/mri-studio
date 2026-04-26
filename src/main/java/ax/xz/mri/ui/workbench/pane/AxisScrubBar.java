package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.ui.theme.StudioTheme;
import ax.xz.mri.util.MathUtil;
import javafx.scene.Cursor;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.MenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;

/** Shared horizontal/vertical scrub bar used to control a visible range inside a larger domain. */
public final class AxisScrubBar {
    public static final double HANDLE_HIT_RADIUS = 7;

    private AxisScrubBar() {
    }

    public enum Orientation {
        HORIZONTAL,
        VERTICAL
    }

    public enum HitRegion {
        NONE,
        WINDOW_START,
        WINDOW_END,
        WINDOW_BODY,
        TRACK
    }

    public record Bounds(double x, double y, double width, double height) {
        public boolean contains(double px, double py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }
    }

    public record Span(double start, double end, Color colour, double opacity) {
    }

    public record Marker(double value, Color colour, double opacity, double lineWidth) {
        public Marker(double value, Color colour) {
            this(value, colour, 0.85, 1.2);
        }
    }

    public record Spec(
        Orientation orientation,
        double domainStart,
        double domainEnd,
        double windowStart,
        double windowEnd,
        List<Span> spans,
        List<Marker> markers,
        Color accent
    ) {
        public Spec {
            spans = spans == null ? List.of() : List.copyOf(spans);
            markers = markers == null ? List.of() : List.copyOf(markers);
            accent = accent == null ? StudioTheme.AC : accent;
        }

        public static Spec horizontal(
            double domainStart,
            double domainEnd,
            double windowStart,
            double windowEnd,
            List<Span> spans,
            List<Marker> markers
        ) {
            return new Spec(Orientation.HORIZONTAL, domainStart, domainEnd, windowStart, windowEnd, spans, markers, StudioTheme.AC);
        }

        public static Spec vertical(
            double domainStart,
            double domainEnd,
            double windowStart,
            double windowEnd,
            List<Span> spans,
            List<Marker> markers
        ) {
            return new Spec(Orientation.VERTICAL, domainStart, domainEnd, windowStart, windowEnd, spans, markers, StudioTheme.AC);
        }
    }

    public interface WindowModel {
        double domainStart();

        double domainEnd();

        double windowStart();

        double windowEnd();

        void setWindow(double start, double end);

        void zoomAround(double anchor, double factor);

        void resetWindow();

        default String resetLabel() {
            return "Zoom Out to Full Range";
        }

        /**
         * Build a {@code WindowModel} from supplier/consumer hooks — collapses
         * the 30-line anonymous-class boilerplate that every analysis pane
         * with an overview scrub bar otherwise repeats.
         *
         * @param resetLabel  context-menu label for the "fit to data" action;
         *                    pass {@code null} for the default
         */
        static WindowModel of(
            java.util.function.DoubleSupplier domainStart,
            java.util.function.DoubleSupplier domainEnd,
            java.util.function.DoubleSupplier windowStart,
            java.util.function.DoubleSupplier windowEnd,
            java.util.function.BiConsumer<Double, Double> setWindow,
            java.util.function.BiConsumer<Double, Double> zoomAround,
            Runnable resetWindow,
            String resetLabel
        ) {
            return new WindowModel() {
                @Override public double domainStart()                             { return domainStart.getAsDouble(); }
                @Override public double domainEnd()                               { return domainEnd.getAsDouble(); }
                @Override public double windowStart()                             { return windowStart.getAsDouble(); }
                @Override public double windowEnd()                               { return windowEnd.getAsDouble(); }
                @Override public void setWindow(double start, double end)         { setWindow.accept(start, end); }
                @Override public void zoomAround(double anchor, double factor)    { zoomAround.accept(anchor, factor); }
                @Override public void resetWindow()                               { resetWindow.run(); }
                @Override public String resetLabel()                              { return resetLabel != null ? resetLabel : "Zoom Out to Full Range"; }
            };
        }
    }

    public static final class Interaction {
        private final Orientation orientation;
        private final WindowModel model;

        private HitRegion dragRegion = HitRegion.NONE;
        private double dragAnchorValue;
        private double dragStartWindowStart;
        private double dragStartWindowEnd;

        public Interaction(Orientation orientation, WindowModel model) {
            this.orientation = orientation;
            this.model = model;
        }

        public boolean handlePress(Bounds bounds, MouseEvent event) {
            if (!bounds.contains(event.getX(), event.getY()) || event.getButton() != MouseButton.PRIMARY) {
                return false;
            }

            if (event.getClickCount() >= 2) {
                model.resetWindow();
                dragRegion = HitRegion.NONE;
                return true;
            }

            dragStartWindowStart = model.windowStart();
            dragStartWindowEnd = model.windowEnd();
            dragAnchorValue = valueAt(bounds, orientation, axisPosition(bounds, orientation, event.getX(), event.getY()), model.domainStart(), model.domainEnd());
            dragRegion = hitTest(bounds, orientation, model.domainStart(), model.domainEnd(), dragStartWindowStart, dragStartWindowEnd, event.getX(), event.getY());
            if (dragRegion == HitRegion.TRACK) {
                double span = dragStartWindowEnd - dragStartWindowStart;
                double centre = dragAnchorValue;
                model.setWindow(centre - span / 2, centre + span / 2);
                dragStartWindowStart = model.windowStart();
                dragStartWindowEnd = model.windowEnd();
                dragRegion = HitRegion.WINDOW_BODY;
            }
            return dragRegion != HitRegion.NONE;
        }

        public boolean handleDrag(Bounds bounds, MouseEvent event) {
            if (dragRegion == HitRegion.NONE) {
                return false;
            }
            double value = valueAt(bounds, orientation, axisPosition(bounds, orientation, event.getX(), event.getY()), model.domainStart(), model.domainEnd());
            switch (dragRegion) {
                case WINDOW_START -> model.setWindow(value, dragStartWindowEnd);
                case WINDOW_END -> model.setWindow(dragStartWindowStart, value);
                case WINDOW_BODY -> {
                    double delta = value - dragAnchorValue;
                    model.setWindow(dragStartWindowStart + delta, dragStartWindowEnd + delta);
                }
                default -> {
                    return false;
                }
            }
            return true;
        }

        public boolean handleScroll(Bounds bounds, ScrollEvent event) {
            if (!bounds.contains(event.getX(), event.getY())) {
                return false;
            }
            double anchor = valueAt(bounds, orientation, axisPosition(bounds, orientation, event.getX(), event.getY()), model.domainStart(), model.domainEnd());
            model.zoomAround(anchor, event.getDeltaY() > 0 ? 0.85 : 1.18);
            return true;
        }

        public void handleRelease() {
            dragRegion = HitRegion.NONE;
        }

        public Cursor cursor(Bounds bounds, double mouseX, double mouseY) {
            if (!bounds.contains(mouseX, mouseY)) {
                return Cursor.DEFAULT;
            }
            HitRegion region = hitTest(bounds, orientation, model.domainStart(), model.domainEnd(), model.windowStart(), model.windowEnd(), mouseX, mouseY);
            return switch (region) {
                case WINDOW_START, WINDOW_END -> orientation == Orientation.HORIZONTAL ? Cursor.H_RESIZE : Cursor.V_RESIZE;
                case WINDOW_BODY -> Cursor.CLOSED_HAND;
                case TRACK -> Cursor.OPEN_HAND;
                default -> Cursor.DEFAULT;
            };
        }

        public MenuItem newResetMenuItem() {
            var item = new MenuItem(model.resetLabel());
            item.setOnAction(event -> model.resetWindow());
            return item;
        }
    }

    public static void draw(GraphicsContext g, Bounds bounds, Spec spec) {
        double domainSpan = Math.max(1e-9, spec.domainEnd() - spec.domainStart());
        double startPixel = pixelAt(bounds, spec.orientation(), spec.windowStart(), spec.domainStart(), spec.domainEnd());
        double endPixel = pixelAt(bounds, spec.orientation(), spec.windowEnd(), spec.domainStart(), spec.domainEnd());
        double windowMin = Math.min(startPixel, endPixel);
        double windowMax = Math.max(startPixel, endPixel);

        g.save();
        g.setFill(Color.color(0.08, 0.12, 0.18, 0.07));
        g.fillRoundRect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), 6, 6);

        for (var span : spec.spans()) {
            double start = Math.max(spec.domainStart(), span.start());
            double end = Math.min(spec.domainEnd(), span.end());
            if (end <= start) continue;
            double p0 = pixelAt(bounds, spec.orientation(), start, spec.domainStart(), spec.domainEnd());
            double p1 = pixelAt(bounds, spec.orientation(), end, spec.domainStart(), spec.domainEnd());
            g.setFill(new Color(span.colour().getRed(), span.colour().getGreen(), span.colour().getBlue(), span.opacity()));
            fillRange(g, bounds, spec.orientation(), p0, p1);
        }

        g.setFill(new Color(spec.accent().getRed(), spec.accent().getGreen(), spec.accent().getBlue(), 0.15));
        fillRange(g, bounds, spec.orientation(), windowMin, windowMax);

        g.setStroke(new Color(spec.accent().getRed(), spec.accent().getGreen(), spec.accent().getBlue(), 0.9));
        g.setLineWidth(1.1);
        strokeWindow(g, bounds, spec.orientation(), windowMin, windowMax);

        for (double handlePixel : new double[]{startPixel, endPixel}) {
            g.setStroke(spec.accent());
            g.setLineWidth(1.4);
            if (spec.orientation() == Orientation.HORIZONTAL) {
                g.strokeLine(handlePixel, bounds.y() + 1.5, handlePixel, bounds.y() + bounds.height() - 1.5);
            } else {
                g.strokeLine(bounds.x() + 1.5, handlePixel, bounds.x() + bounds.width() - 1.5, handlePixel);
            }
        }

        for (var marker : spec.markers()) {
            if (marker.value() < spec.domainStart() || marker.value() > spec.domainEnd()) continue;
            double pixel = pixelAt(bounds, spec.orientation(), marker.value(), spec.domainStart(), spec.domainEnd());
            g.setStroke(new Color(marker.colour().getRed(), marker.colour().getGreen(), marker.colour().getBlue(), marker.opacity()));
            g.setLineWidth(marker.lineWidth());
            if (spec.orientation() == Orientation.HORIZONTAL) {
                g.strokeLine(pixel, bounds.y(), pixel, bounds.y() + bounds.height());
            } else {
                g.strokeLine(bounds.x(), pixel, bounds.x() + bounds.width(), pixel);
            }
        }

        if (domainSpan > 0) {
            g.setStroke(Color.color(0, 0, 0, 0.12));
            g.setLineWidth(0.8);
            g.strokeRoundRect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), 6, 6);
        }
        g.restore();
    }

    public static List<Span> rfSpans(BlochData data, List<PulseSegment> pulse, Color colour, double opacity) {
        var spans = new ArrayList<Span>();
        if (data == null || data.field() == null || data.field().segments == null || pulse == null) return spans;

        double accumulatedTime = 0;
        for (int segmentIndex = 0; segmentIndex < data.field().segments.size() && segmentIndex < pulse.size(); segmentIndex++) {
            var segment = data.field().segments.get(segmentIndex);
            var steps = pulse.get(segmentIndex).steps();
            Double rfStart = null;
            double stepTime = accumulatedTime;
            for (var step : steps) {
                if (step.isRfOn() && rfStart == null) rfStart = stepTime;
                if (!step.isRfOn() && rfStart != null) {
                    spans.add(new Span(rfStart, stepTime, colour, opacity));
                    rfStart = null;
                }
                stepTime += segment.dt() * 1e6;
            }
            if (rfStart != null) {
                spans.add(new Span(rfStart, stepTime, colour, opacity));
            }
            accumulatedTime += segment.totalSteps() * segment.dt() * 1e6;
        }
        return spans;
    }

    public static double pixelAt(Bounds bounds, Orientation orientation, double value, double domainStart, double domainEnd) {
        double normalized = (value - domainStart) / Math.max(1e-9, domainEnd - domainStart);
        if (orientation == Orientation.HORIZONTAL) {
            return bounds.x() + normalized * bounds.width();
        }
        return bounds.y() + bounds.height() - normalized * bounds.height();
    }

    public static double valueAt(Bounds bounds, Orientation orientation, double axisPixel, double domainStart, double domainEnd) {
        if (orientation == Orientation.HORIZONTAL) {
            double clamped = MathUtil.clamp(axisPixel, bounds.x(), bounds.x() + bounds.width());
            double t = (clamped - bounds.x()) / Math.max(1, bounds.width());
            return domainStart + t * (domainEnd - domainStart);
        }
        double clamped = MathUtil.clamp(axisPixel, bounds.y(), bounds.y() + bounds.height());
        double t = 1 - (clamped - bounds.y()) / Math.max(1, bounds.height());
        return domainStart + t * (domainEnd - domainStart);
    }

    public static HitRegion hitTest(
        Bounds bounds,
        Orientation orientation,
        double domainStart,
        double domainEnd,
        double windowStart,
        double windowEnd,
        double mouseX,
        double mouseY
    ) {
        if (!bounds.contains(mouseX, mouseY)) {
            return HitRegion.NONE;
        }
        double axis = axisPosition(bounds, orientation, mouseX, mouseY);
        double start = pixelAt(bounds, orientation, windowStart, domainStart, domainEnd);
        double end = pixelAt(bounds, orientation, windowEnd, domainStart, domainEnd);
        double min = Math.min(start, end);
        double max = Math.max(start, end);
        if (Math.abs(axis - start) <= HANDLE_HIT_RADIUS) return HitRegion.WINDOW_START;
        if (Math.abs(axis - end) <= HANDLE_HIT_RADIUS) return HitRegion.WINDOW_END;
        if (axis > min && axis < max) return HitRegion.WINDOW_BODY;
        return HitRegion.TRACK;
    }

    private static double axisPosition(Bounds bounds, Orientation orientation, double mouseX, double mouseY) {
        return orientation == Orientation.HORIZONTAL ? mouseX : mouseY;
    }

    private static void fillRange(GraphicsContext g, Bounds bounds, Orientation orientation, double p0, double p1) {
        double min = Math.min(p0, p1);
        double max = Math.max(p0, p1);
        if (orientation == Orientation.HORIZONTAL) {
            g.fillRoundRect(min, bounds.y(), Math.max(8, max - min), bounds.height(), 5, 5);
            return;
        }
        g.fillRoundRect(bounds.x(), min, bounds.width(), Math.max(8, max - min), 5, 5);
    }

    private static void strokeWindow(GraphicsContext g, Bounds bounds, Orientation orientation, double min, double max) {
        if (orientation == Orientation.HORIZONTAL) {
            g.strokeRoundRect(min, bounds.y() + 0.5, Math.max(8, max - min), bounds.height() - 1, 5, 5);
            return;
        }
        g.strokeRoundRect(bounds.x() + 0.5, min, bounds.width() - 1, Math.max(8, max - min), 5, 5);
    }
}
