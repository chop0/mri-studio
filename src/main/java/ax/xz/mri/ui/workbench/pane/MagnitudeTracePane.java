package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.ui.theme.StudioTheme;
import ax.xz.mri.ui.viewmodel.PulseTimelineAnalysis;
import ax.xz.mri.ui.workbench.PaneContext;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.MenuButton;
import javafx.scene.paint.Color;

/** |M⊥| trace pane. */
public class MagnitudeTracePane extends AbstractTracePlotPane {
    private final BooleanProperty showRfMarkers = new SimpleBooleanProperty(true);
    private final BooleanProperty showFreeMarkers = new SimpleBooleanProperty(false);
    private final BooleanProperty showSegmentMarkers = new SimpleBooleanProperty(false);
    private final BooleanProperty showAverageDots = new SimpleBooleanProperty(true);
    private PulseTimelineAnalysis.MeasurementWindow hoveredMeasurement;

    public MagnitudeTracePane(PaneContext paneContext) {
        super(paneContext, paneContext.session().traceMagnitude);
        var markerMenu = new MenuButton("Markers");
        markerMenu.getItems().addAll(
            markerToggle("TX Windows", showRfMarkers),
            markerToggle("Receive Windows", showFreeMarkers),
            markerToggle("Segment Boundaries", showSegmentMarkers)
        );

        var averageDots = new CheckBox("Avg markers");
        averageDots.selectedProperty().bindBidirectional(showAverageDots);

        setToolNodes(markerMenu, averageDots);
        bindRedraw(
            showRfMarkers,
            showFreeMarkers,
            showSegmentMarkers,
            showAverageDots,
            paneContext.session().derived.signalTrace
        );
    }

    @Override
    protected void drawPlotMarkers(
        GraphicsContext g,
        PulseTimelineAnalysis.Analysis analysis,
        double plotWidth,
        double plotHeight,
        double tMin,
        double tMax,
        double tSpan
    ) {
        if (showFreeMarkers.get()) {
            g.setFill(Color.color(0.18, 0.49, 0.20, 0.05));
            for (var window : analysis.freeWindows()) {
                if (window.endMicros() < tMin || window.startMicros() > tMax) continue;
                double x0 = Math.max(PAD_LEFT, xForTime(window.startMicros(), tMin, tSpan, plotWidth));
                double x1 = Math.min(PAD_LEFT + plotWidth, xForTime(window.endMicros(), tMin, tSpan, plotWidth));
                g.fillRect(x0, PAD_TOP, Math.max(0, x1 - x0), plotHeight);
            }
        }

        if (showRfMarkers.get()) {
            g.setFill(Color.color(0.08, 0.40, 0.75, 0.08));
            for (var window : analysis.rfWindows()) {
                if (window.endMicros() < tMin || window.startMicros() > tMax) continue;
                double x0 = Math.max(PAD_LEFT, xForTime(window.startMicros(), tMin, tSpan, plotWidth));
                double x1 = Math.min(PAD_LEFT + plotWidth, xForTime(window.endMicros(), tMin, tSpan, plotWidth));
                g.fillRect(x0, PAD_TOP, Math.max(0, x1 - x0), plotHeight);
            }
        }

        if (showSegmentMarkers.get()) {
            g.setStroke(Color.color(0, 0, 0, 0.10));
            g.setLineWidth(0.6);
            g.setLineDashes(4, 4);
            for (int index = 1; index < analysis.segmentWindows().size(); index++) {
                double divider = analysis.segmentWindows().get(index).startMicros();
                if (divider < tMin || divider > tMax) continue;
                double x = xForTime(divider, tMin, tSpan, plotWidth);
                g.strokeLine(x, PAD_TOP, x, PAD_TOP + plotHeight);
            }
            g.setLineDashes();
        }
    }

    @Override
    protected void drawPlotAnnotations(
        GraphicsContext g,
        PulseTimelineAnalysis.Analysis analysis,
        double plotWidth,
        double plotHeight,
        double tMin,
        double tMax,
        double tSpan
    ) {
        if (!showAverageDots.get()) return;

        for (var measurement : analysis.measurements()) {
            if (measurement.centerMicros() < tMin || measurement.centerMicros() > tMax) continue;
            double x = xForTime(measurement.centerMicros(), tMin, tSpan, plotWidth);
            double y = yForValue(measurement.normalizedAverage(), plotHeight);
            Color fill = Color.color(StudioTheme.AC.getRed(), StudioTheme.AC.getGreen(), StudioTheme.AC.getBlue(), 0.95);
            Color stroke = Color.color(0.15, 0.16, 0.19, 0.8);
            if (measurement.equals(hoveredMeasurement)) {
                g.setFill(Color.color(fill.getRed(), fill.getGreen(), fill.getBlue(), 0.12));
                double x0 = Math.max(PAD_LEFT, xForTime(measurement.startMicros(), tMin, tSpan, plotWidth));
                double x1 = Math.min(PAD_LEFT + plotWidth, xForTime(measurement.endMicros(), tMin, tSpan, plotWidth));
                g.fillRect(x0, PAD_TOP, Math.max(0, x1 - x0), plotHeight);
            }
            g.setFill(fill);
            g.fillOval(x - 3.5, y - 3.5, 7, 7);
            g.setStroke(stroke);
            g.setLineWidth(0.9);
            g.strokeOval(x - 3.5, y - 3.5, 7, 7);
            if (measurement.equals(hoveredMeasurement)) {
                g.setStroke(fill);
                g.setLineWidth(1.4);
                g.strokeOval(x - 5, y - 5, 10, 10);
            }
        }
    }

    @Override
    protected void updateCustomHover(double mouseX, double mouseY, double tMin, double tMax, double plotWidth, double plotHeight) {
        hoveredMeasurement = null;
        var analysis = pulseTimelineAnalysis();
        double tSpan = Math.max(1.0, tMax - tMin);
        for (var measurement : analysis.measurements()) {
            if (measurement.endMicros() < tMin || measurement.startMicros() > tMax) continue;
            double x0 = Math.max(PAD_LEFT, xForTime(measurement.startMicros(), tMin, tSpan, plotWidth));
            double x1 = Math.min(PAD_LEFT + plotWidth, xForTime(measurement.endMicros(), tMin, tSpan, plotWidth));
            double dotX = xForTime(measurement.centerMicros(), tMin, tSpan, plotWidth);
            double dotY = yForValue(measurement.normalizedAverage(), plotHeight);
            boolean nearDot = Math.hypot(mouseX - dotX, mouseY - dotY) <= 8.0;
            boolean insideWindow = mouseX >= x0 && mouseX <= x1 && mouseY >= PAD_TOP && mouseY <= PAD_TOP + plotHeight;
            if (nearDot || insideWindow) {
                hoveredMeasurement = measurement;
                return;
            }
        }
    }

    @Override
    protected void clearCustomHover() {
        hoveredMeasurement = null;
    }

    @Override
    protected boolean populateCustomContextMenu(javafx.scene.control.ContextMenu menu, double mouseX, double mouseY) {
        if (hoveredMeasurement == null) return false;
        var title = new javafx.scene.control.MenuItem("Receive Window");
        title.setDisable(true);
        var setCursor = new javafx.scene.control.MenuItem("Set Cursor To Window Centre");
        setCursor.setOnAction(event -> paneContext.session().viewport.setCursor(hoveredMeasurement.centerMicros()));
        var zoom = new javafx.scene.control.MenuItem("Zoom To Window");
        zoom.setOnAction(event -> {
            double span = Math.max(10.0, hoveredMeasurement.endMicros() - hoveredMeasurement.startMicros());
            double margin = span * 0.35;
            paneContext.session().timeline.viewportController.setViewport(
                hoveredMeasurement.startMicros() - margin,
                hoveredMeasurement.endMicros() + margin
            );
        });
        var useAnalysis = new javafx.scene.control.MenuItem("Use As Analysis Window");
        useAnalysis.setOnAction(event ->
            paneContext.session().viewport.setAnalysisWindow(hoveredMeasurement.startMicros(), hoveredMeasurement.endMicros()));
        menu.getItems().addAll(title, new javafx.scene.control.SeparatorMenuItem(), setCursor, zoom, useAnalysis);
        return true;
    }

    @Override
    protected void drawCustomHoverOverlay(
        GraphicsContext g,
        double plotWidth,
        double plotHeight,
        double tMin,
        double tMax,
        double tSpan
    ) {
        if (hoveredMeasurement == null) return;
        drawInfoCard(
            g,
            xForTime(hoveredMeasurement.centerMicros(), tMin, tSpan, plotWidth),
            yForValue(hoveredMeasurement.normalizedAverage(), plotHeight),
            StudioTheme.AC,
            java.util.List.of(
                "Average receive signal",
                String.format("<S> = %.3f", hoveredMeasurement.averageSignal()),
                String.format("norm = %.3f", hoveredMeasurement.normalizedAverage()),
                String.format("t = %.1f .. %.1f \u03bcs", hoveredMeasurement.startMicros(), hoveredMeasurement.endMicros()),
                String.format("\u0394t = %.1f \u03bcs", hoveredMeasurement.endMicros() - hoveredMeasurement.startMicros())
            )
        );
    }

    private static CheckMenuItem markerToggle(String label, BooleanProperty property) {
        var item = new CheckMenuItem(label);
        item.selectedProperty().bindBidirectional(property);
        return item;
    }
}
