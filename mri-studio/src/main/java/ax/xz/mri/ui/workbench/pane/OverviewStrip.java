package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;

/** Shared thin overview strip used to navigate larger axis ranges. */
public final class OverviewStrip {
    public static final double HANDLE_HIT_RADIUS = 6;

    private OverviewStrip() {
    }

    public record Bounds(double x, double y, double width, double height) {
        public boolean contains(double px, double py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }
    }

    public record Span(double start, double end, Color colour, double opacity) {
    }

    public static void drawHorizontal(
        GraphicsContext g,
        Bounds bounds,
        double domainStart,
        double domainEnd,
        double windowStart,
        double windowEnd,
        List<Span> spans,
        Double cursor
    ) {
        double domainSpan = Math.max(1e-9, domainEnd - domainStart);
        g.save();
        g.setFill(Color.color(0, 0, 0, 0.05));
        g.fillRoundRect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), 6, 6);

        for (var span : spans) {
            double start = Math.max(domainStart, span.start());
            double end = Math.min(domainEnd, span.end());
            if (end <= start) continue;
            double x0 = pixelAt(bounds, start, domainStart, domainEnd);
            double x1 = pixelAt(bounds, end, domainStart, domainEnd);
            g.setFill(withOpacity(span.colour(), span.opacity()));
            g.fillRoundRect(x0, bounds.y(), Math.max(1, x1 - x0), bounds.height(), 4, 4);
        }

        double windowX0 = pixelAt(bounds, windowStart, domainStart, domainEnd);
        double windowX1 = pixelAt(bounds, windowEnd, domainStart, domainEnd);
        g.setFill(Color.color(0.0, 0.47, 0.83, 0.16));
        g.fillRoundRect(windowX0, bounds.y(), Math.max(8, windowX1 - windowX0), bounds.height(), 5, 5);
        g.setStroke(Color.web("#0078d4"));
        g.setLineWidth(1);
        g.strokeRoundRect(windowX0, bounds.y() + 0.5, Math.max(8, windowX1 - windowX0), bounds.height() - 1, 5, 5);

        for (double handleX : new double[]{windowX0, windowX1}) {
            g.setStroke(Color.web("#0078d4"));
            g.setLineWidth(1.2);
            g.strokeLine(handleX, bounds.y() + 1.5, handleX, bounds.y() + bounds.height() - 1.5);
        }

        if (cursor != null && cursor >= domainStart && cursor <= domainEnd) {
            double cursorX = pixelAt(bounds, cursor, domainStart, domainEnd);
            g.setStroke(Color.web("#e06000"));
            g.setLineWidth(1);
            g.strokeLine(cursorX, bounds.y(), cursorX, bounds.y() + bounds.height());
        }

        g.setStroke(Color.color(0, 0, 0, 0.12));
        g.setLineWidth(0.8);
        g.strokeRoundRect(bounds.x(), bounds.y(), bounds.width(), bounds.height(), 6, 6);
        g.restore();
    }

    public static double pixelAt(Bounds bounds, double value, double domainStart, double domainEnd) {
        double domainSpan = Math.max(1e-9, domainEnd - domainStart);
        return bounds.x() + (value - domainStart) / domainSpan * bounds.width();
    }

    public static double valueAt(Bounds bounds, double mouseX, double domainStart, double domainEnd) {
        double clampedX = Math.max(bounds.x(), Math.min(bounds.x() + bounds.width(), mouseX));
        return domainStart + (clampedX - bounds.x()) / bounds.width() * (domainEnd - domainStart);
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

    private static Color withOpacity(Color colour, double opacity) {
        return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), opacity);
    }
}
