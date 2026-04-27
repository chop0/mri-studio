package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.ui.model.IsochromatEntry;
import ax.xz.mri.ui.theme.StudioTheme;
import ax.xz.mri.ui.viewmodel.PulseTimelineAnalysis;
import ax.xz.mri.ui.viewmodel.ReferenceFrameUtil;
import ax.xz.mri.ui.viewmodel.TracePlotViewModel;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.CanvasWorkbenchPane;
import ax.xz.mri.util.MathUtil;
import ax.xz.mri.util.SiFormat;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

import static ax.xz.mri.ui.theme.StudioTheme.BG;
import static ax.xz.mri.ui.theme.StudioTheme.CUR;
import static ax.xz.mri.ui.theme.StudioTheme.TX;
import static ax.xz.mri.ui.theme.StudioTheme.TX2;
import static ax.xz.mri.ui.theme.StudioTheme.UI_7;
import static ax.xz.mri.ui.theme.StudioTheme.UI_8;
import static ax.xz.mri.ui.theme.StudioTheme.UI_BOLD_10;

/** Shared single trace-plot pane. */
public abstract class AbstractTracePlotPane extends CanvasWorkbenchPane {
    protected static final double OVERVIEW_H = 10;
    protected static final double OVERVIEW_GAP = 6;
    protected static final double LEGEND_H = 16;
    protected static final double TITLE_H = 14;
    protected static final double PAD_LEFT = 40;
    protected static final double PAD_RIGHT = 8;
    protected static final double PAD_TOP = 14 + OVERVIEW_H + OVERVIEW_GAP + LEGEND_H + TITLE_H;
    protected static final double PAD_BOTTOM = 18;

    private record TraceHoverTarget(
        IsochromatEntry entry,
        double timeMicros,
        double value,
        double x,
        double y,
        double mx,
        double my,
        double mz
    ) {
    }

    private final TracePlotViewModel viewModel;
    private final AxisScrubBar.Interaction overviewInteraction;
    private boolean hoveringPlot;
    private double hoveredTimeMicros;
    private double hoveredMouseX;
    private double hoveredMouseY;
    private TraceHoverTarget hoveredTraceTarget;

    protected AbstractTracePlotPane(PaneContext paneContext, TracePlotViewModel viewModel) {
        super(paneContext);
        this.viewModel = viewModel;
        var viewport = paneContext.session().viewport;
        this.overviewInteraction = new AxisScrubBar.Interaction(
            AxisScrubBar.Orientation.HORIZONTAL,
            AxisScrubBar.WindowModel.of(
                () -> 0,
                () -> Math.max(viewport.maxTime.get(), 1),
                viewport.tS::get, viewport.tE::get,
                viewport::setAnalysisWindow,
                viewport::zoomAnalysisWindowAround,
                viewport::fitAnalysisToData,
                null
            )
        );
        setPaneTitle(viewModel.title());
        bindRedraw(
            paneContext.session().points.entries,
            paneContext.session().selection.selectedIds,
            paneContext.session().viewport.tS,
            paneContext.session().viewport.tE,
            paneContext.session().viewport.tC,
            paneContext.session().viewport.maxTime,
            paneContext.session().document.currentPulse,
            paneContext.session().document.blochData,
            paneContext.session().reference.enabled,
            paneContext.session().reference.r,
            paneContext.session().reference.z,
            paneContext.session().reference.trajectory
        );

        canvas.setOnMousePressed(event -> {
            if (!event.isPrimaryButtonDown()) return;
            if (overviewInteraction.handlePress(overviewBounds(), event)) {
                updateHover(event.getX(), event.getY());
                updateStatus(event.getX());
                scheduleRedraw();
                return;
            }
            moveCursor(event.getX());
        });
        canvas.setOnMouseDragged(event -> {
            if (overviewInteraction.handleDrag(overviewBounds(), event)) {
                updateHover(event.getX(), event.getY());
                updateStatus(event.getX());
                scheduleRedraw();
                return;
            }
            updateHover(event.getX(), event.getY());
            moveCursor(event.getX());
        });
        canvas.setOnMouseReleased(event -> overviewInteraction.handleRelease());
        canvas.setOnMouseMoved(event -> {
            updateHover(event.getX(), event.getY());
            updateStatus(event.getX());
        });
        canvas.setOnScroll(event -> {
            if (overviewInteraction.handleScroll(overviewBounds(), event)) {
                scheduleRedraw();
                updateStatus(event.getX());
            }
        });
        canvas.setOnContextMenuRequested(event -> {
            var menu = buildContextMenu(event.getX(), event.getY());
            showCanvasContextMenu(menu, event.getScreenX(), event.getScreenY());
        });
    }

    @Override
    protected void paint(GraphicsContext g, double width, double height) {
        g.setFill(BG);
        g.fillRect(0, 0, width, height);

        double plotWidth = width - PAD_LEFT - PAD_RIGHT;
        double plotHeight = height - PAD_TOP - PAD_BOTTOM;
        double tMin = paneContext.session().viewport.tS.get();
        double tMax = Math.max(paneContext.session().viewport.tE.get(), tMin + 1);
        double tSpan = tMax - tMin;
        double cursorTime = paneContext.session().viewport.tC.get();
        var referenceTrajectory = paneContext.session().reference.enabled.get()
            ? paneContext.session().reference.trajectory.get()
            : null;
        var pulseAnalysis = pulseTimelineAnalysis();
        AxisScrubBar.draw(
            g,
            overviewBounds(width),
            AxisScrubBar.Spec.horizontal(
                0,
                Math.max(paneContext.session().viewport.maxTime.get(), 1),
                tMin,
                tMax,
                rfSpans(),
                java.util.List.of(new AxisScrubBar.Marker(cursorTime, CUR, 0.85, 1.0))
            )
        );

        g.setTextAlign(TextAlignment.RIGHT);
        for (double tick : viewModel.ticks()) {
            double y = PAD_TOP + plotHeight - (tick - viewModel.min()) / (viewModel.max() - viewModel.min()) * plotHeight;
            g.setStroke(Color.color(0, 0, 0, tick == 0 ? 0.15 : 0.06));
            g.setLineWidth(tick == 0 ? 0.6 : 0.3);
            g.strokeLine(PAD_LEFT, y, PAD_LEFT + plotWidth, y);
            g.setFill(TX);
            g.setFont(UI_8);
            String label = "\u00b0".equals(viewModel.unit()) ? (int) tick + "\u00b0"
                : (tick % 1 != 0 ? String.format("%.2f", tick) : String.valueOf((int) tick));
            g.fillText(label, PAD_LEFT - 4, y + 3);
        }
        g.setTextAlign(TextAlignment.LEFT);

        g.setStroke(Color.color(0, 0, 0, 0.15));
        g.setLineWidth(0.5);
        g.beginPath();
        g.moveTo(PAD_LEFT, PAD_TOP);
        g.lineTo(PAD_LEFT, PAD_TOP + plotHeight);
        g.lineTo(PAD_LEFT + plotWidth, PAD_TOP + plotHeight);
        g.stroke();
        drawPlotMarkers(g, pulseAnalysis, plotWidth, plotHeight, tMin, tMax, tSpan);

        int xTickStep = niceTick(tSpan);
        g.setFill(TX2);
        g.setFont(UI_7);
        g.setTextAlign(TextAlignment.CENTER);
        g.setGlobalAlpha(0.6);
        for (double tick = Math.ceil(tMin / xTickStep) * xTickStep; tick <= tMax; tick += xTickStep) {
            double x = PAD_LEFT + (tick - tMin) / tSpan * plotWidth;
            if (x > PAD_LEFT + 4 && x < PAD_LEFT + plotWidth - 4) {
                String label = tSpan > 2000
                    ? String.format("%.0f", tick / 1000) + (tick % 1000 != 0 ? String.format(".%01.0f", (tick % 1000) / 100) : "")
                    : String.valueOf((int) tick);
                g.fillText(label, x, PAD_TOP + plotHeight + 10);
                g.setStroke(Color.color(0, 0, 0, 0.04));
                g.setLineWidth(0.3);
                g.strokeLine(x, PAD_TOP, x, PAD_TOP + plotHeight);
            }
        }
        if (!viewModel.unit().isEmpty()) {
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(tSpan > 2000 ? "ms" : "\u03bcs", PAD_LEFT + plotWidth, PAD_TOP + plotHeight + 10);
            g.setTextAlign(TextAlignment.LEFT);
        }
        g.setGlobalAlpha(1);

        double cursorX = PAD_LEFT + (cursorTime - tMin) / tSpan * plotWidth;
        g.setStroke(CUR);
        g.setLineWidth(1);
        g.setGlobalAlpha(0.5);
        g.strokeLine(cursorX, PAD_TOP, cursorX, PAD_TOP + plotHeight);
        g.setGlobalAlpha(1);
        drawBadge(g, cursorX, PAD_TOP + 4, SiFormat.time(cursorTime), CUR);

        g.save();
        g.beginPath();
        g.rect(PAD_LEFT, PAD_TOP - 1, plotWidth, plotHeight + 2);
        g.clip();
        for (var entry : paneContext.session().points.entries) {
            if (!entry.visible() || entry.trajectory() == null) continue;
            boolean selected = paneContext.session().selection.isSelected(entry.id());
            g.setStroke(entry.colour());
            g.setLineWidth(selected ? 2.2 : 1.2);
            g.setGlobalAlpha(selected ? 1.0 : 0.85);
            g.beginPath();
            boolean started = false;
            for (int pointIndex = 0; pointIndex < entry.trajectory().pointCount(); pointIndex += 5) {
                double t = entry.trajectory().tAt(pointIndex);
                if (t < tMin - tSpan * 0.02 || t > tMax + tSpan * 0.02) continue;
                var rotated = ReferenceFrameUtil.rotateIntoReferenceFrame(
                    entry.trajectory().mxAt(pointIndex),
                    entry.trajectory().myAt(pointIndex),
                    entry.trajectory().mzAt(pointIndex),
                    referenceTrajectory,
                    pointIndex,
                    t
                );
                double value = evalPlot(rotated.mx(), rotated.my(), rotated.mz());
                if (Double.isNaN(value)) {
                    started = false;
                    continue;
                }
                double x = PAD_LEFT + (t - tMin) / tSpan * plotWidth;
                double y = PAD_TOP + plotHeight - (value - viewModel.min()) / (viewModel.max() - viewModel.min()) * plotHeight;
                if (!started) {
                    g.moveTo(x, y);
                    started = true;
                } else {
                    g.lineTo(x, y);
                }
            }
            g.stroke();
            g.setGlobalAlpha(1);

            var cursorState = entry.trajectory().interpolateAt(cursorTime);
            if (cursorState != null) {
                var rotatedState = ReferenceFrameUtil.rotateIntoReferenceFrame(cursorState, referenceTrajectory, cursorTime);
                double value = evalPlot(rotatedState.mx(), rotatedState.my(), rotatedState.mz());
                if (!Double.isNaN(value)) {
                    double y = PAD_TOP + plotHeight - (value - viewModel.min()) / (viewModel.max() - viewModel.min()) * plotHeight;
                    g.setFill(entry.colour());
                    double radius = selected ? 4 : 3;
                    g.fillOval(cursorX - radius, y - radius, radius * 2, radius * 2);
                }
            }
        }
        if (hoveringPlot) {
            double hoverX = PAD_LEFT + (hoveredTimeMicros - tMin) / tSpan * plotWidth;
            g.setStroke(Color.color(StudioTheme.AC.getRed(), StudioTheme.AC.getGreen(), StudioTheme.AC.getBlue(), 0.35));
            g.setLineWidth(0.8);
            g.setLineDashes(4, 3);
            g.strokeLine(hoverX, PAD_TOP, hoverX, PAD_TOP + plotHeight);
            g.setLineDashes();
            drawBadge(g, hoverX, PAD_TOP + plotHeight - 18, String.format("t=%.0f \u03bcs", hoveredTimeMicros), StudioTheme.AC);
        }
        drawPlotAnnotations(g, pulseAnalysis, plotWidth, plotHeight, tMin, tMax, tSpan);
        g.restore();
        drawTraceHoverOverlay(g);
        drawCustomHoverOverlay(g, plotWidth, plotHeight, tMin, tMax, tSpan);
        drawLegend(g, width);
    }

    private double evalPlot(double mx, double my, double mz) {
        return switch (viewModel.kind()) {
            case PHASE -> {
                double magnitude = Math.sqrt(mx * mx + my * my);
                yield magnitude > 0.01 ? Math.atan2(my, mx) * 180.0 / Math.PI : Double.NaN;
            }
            case POLAR -> Math.atan2(Math.sqrt(mx * mx + my * my), mz) * 180.0 / Math.PI;
            case MPERP -> Math.sqrt(mx * mx + my * my);
        };
    }

    private void moveCursor(double mouseX) {
        double tMin = paneContext.session().viewport.tS.get();
        double tMax = Math.max(paneContext.session().viewport.tE.get(), tMin + 1);
        double plotWidth = canvas.getWidth() - PAD_LEFT - PAD_RIGHT;
        double time = tMin + (mouseX - PAD_LEFT) / plotWidth * (tMax - tMin);
        paneContext.session().viewport.setCursor(time);
    }

    private void updateStatus(double mouseX) {
        double tMin = paneContext.session().viewport.tS.get();
        double tMax = Math.max(paneContext.session().viewport.tE.get(), tMin + 1);
        double plotWidth = canvas.getWidth() - PAD_LEFT - PAD_RIGHT;
        double time = tMin + (mouseX - PAD_LEFT) / plotWidth * (tMax - tMin);
        if (hoveredTraceTarget != null) {
            setPaneStatus(String.format(
                "%s | t=%.1f \u03bcs | %s=%s | M=(%.3f, %.3f, %.3f) | (r=%.1f mm, z=%.1f mm)",
                hoveredTraceTarget.entry().name(),
                hoveredTraceTarget.timeMicros(),
                viewModel.title(),
                formatPlotValue(hoveredTraceTarget.value()),
                hoveredTraceTarget.mx(),
                hoveredTraceTarget.my(),
                hoveredTraceTarget.mz(),
                hoveredTraceTarget.entry().r(),
                hoveredTraceTarget.entry().z()
            ));
            return;
        }
        long visible = paneContext.session().points.entries.stream()
            .filter(entry -> entry.visible() && entry.trajectory() != null)
            .count();
        setPaneStatus(String.format("t=%.1f \u03bcs | %d traces visible", time, visible));
    }

    private static int niceTick(double span) {
        return span > 5000 ? 2000 : span > 2000 ? 1000 : span > 800 ? 200 : span > 300 ? 100 : 50;
    }

    private void updateHover(double mouseX, double mouseY) {
        double plotWidth = canvas.getWidth() - PAD_LEFT - PAD_RIGHT;
        double plotHeight = canvas.getHeight() - PAD_TOP - PAD_BOTTOM;
        boolean nextHover = mouseX >= PAD_LEFT && mouseX <= PAD_LEFT + plotWidth
            && mouseY >= PAD_TOP && mouseY <= PAD_TOP + plotHeight;
        if (!nextHover) {
            if (hoveringPlot) {
                hoveringPlot = false;
                hoveredTraceTarget = null;
                clearCustomHover();
                scheduleRedraw();
            }
            return;
        }
        double tMin = paneContext.session().viewport.tS.get();
        double tMax = Math.max(paneContext.session().viewport.tE.get(), tMin + 1);
        hoveringPlot = true;
        hoveredMouseX = mouseX;
        hoveredMouseY = mouseY;
        hoveredTimeMicros = tMin + (mouseX - PAD_LEFT) / Math.max(1, plotWidth) * (tMax - tMin);
        hoveredTraceTarget = findHoveredTraceTarget(mouseX, mouseY, tMin, tMax, plotWidth, plotHeight);
        updateCustomHover(mouseX, mouseY, tMin, tMax, plotWidth, plotHeight);
        scheduleRedraw();
    }

    private java.util.List<AxisScrubBar.Span> rfSpans() {
        return AxisScrubBar.rfSpans(
            paneContext.session().document.blochData.get(),
            paneContext.session().document.currentPulse.get(),
            Color.web("#1565c0"),
            0.20
        );
    }

    protected void drawPlotMarkers(
        GraphicsContext g,
        PulseTimelineAnalysis.Analysis analysis,
        double plotWidth,
        double plotHeight,
        double tMin,
        double tMax,
        double tSpan
    ) {
    }

    protected void drawPlotAnnotations(
        GraphicsContext g,
        PulseTimelineAnalysis.Analysis analysis,
        double plotWidth,
        double plotHeight,
        double tMin,
        double tMax,
        double tSpan
    ) {
    }

    protected void drawCustomHoverOverlay(
        GraphicsContext g,
        double plotWidth,
        double plotHeight,
        double tMin,
        double tMax,
        double tSpan
    ) {
    }

    protected final PulseTimelineAnalysis.Analysis pulseTimelineAnalysis() {
        return PulseTimelineAnalysis.compute(
            paneContext.session().document.blochData.get(),
            paneContext.session().document.currentPulse.get(),
            paneContext.session().derived.signalTrace.get()
        );
    }

    protected final TracePlotViewModel viewModel() {
        return viewModel;
    }

    protected final double xForTime(double timeMicros, double tMin, double tSpan, double plotWidth) {
        return PAD_LEFT + (timeMicros - tMin) / tSpan * plotWidth;
    }

    protected final double yForValue(double value, double plotHeight) {
        return PAD_TOP + plotHeight - (value - viewModel.min()) / (viewModel.max() - viewModel.min()) * plotHeight;
    }

    private void drawLegend(GraphicsContext g, double width) {
        double x = PAD_LEFT + 4;
        double y = PAD_TOP - TITLE_H - LEGEND_H + 2;
        int drawn = 0;
        for (var entry : paneContext.session().points.entries) {
            if (!entry.visible() || entry.trajectory() == null) continue;
            boolean selected = paneContext.session().selection.isSelected(entry.id());
            double chipWidth = Math.max(34, entry.name().length() * 4.8 + 14);
            if (x + chipWidth > width - PAD_RIGHT - 4 || drawn >= 3) break;
            g.setFill(Color.color(entry.colour().getRed(), entry.colour().getGreen(), entry.colour().getBlue(), selected ? 0.18 : 0.10));
            g.fillRoundRect(x, y, chipWidth, 12, 6, 6);
            g.setStroke(Color.color(entry.colour().getRed(), entry.colour().getGreen(), entry.colour().getBlue(), selected ? 0.85 : 0.45));
            g.setLineWidth(selected ? 0.9 : 0.6);
            g.strokeRoundRect(x, y, chipWidth, 12, 6, 6);
            g.setFill(entry.colour());
            g.fillOval(x + 3, y + 3.5, 5, 5);
            g.setFill(StudioTheme.TX);
            g.setFont(UI_7);
            g.fillText(entry.name(), x + 11, y + 8.6);
            x += chipWidth + 4;
            drawn++;
        }
    }

    protected void updateCustomHover(double mouseX, double mouseY, double tMin, double tMax, double plotWidth, double plotHeight) {
    }

    protected void clearCustomHover() {
    }

    protected boolean populateCustomContextMenu(ContextMenu menu, double mouseX, double mouseY) {
        return false;
    }

    private void drawBadge(GraphicsContext g, double centerX, double y, String text, Color accent) {
        g.setFont(UI_7);
        double width = Math.max(40, text.length() * 4.9);
        double x = MathUtil.clamp(centerX - width / 2, PAD_LEFT + 2, canvas.getWidth() - PAD_RIGHT - width - 2);
        g.setFill(Color.color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0.10));
        g.fillRoundRect(x, y, width, 12, 6, 6);
        g.setStroke(Color.color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0.45));
        g.setLineWidth(0.6);
        g.strokeRoundRect(x, y, width, 12, 6, 6);
        g.setFill(Color.color(0.18, 0.2, 0.24, 0.9));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(text, x + width / 2, y + 8.2);
        g.setTextAlign(TextAlignment.LEFT);
    }

    protected final void drawInfoCard(GraphicsContext g, double anchorX, double anchorY, Color accent, java.util.List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        g.setFont(UI_7);
        int maxLength = lines.stream().mapToInt(String::length).max().orElse(8);
        double width = Math.max(96, maxLength * 5.2 + 16);
        double height = lines.size() * 11 + 10;
        double x = MathUtil.clamp(anchorX + 10, 6, canvas.getWidth() - width - 6);
        double y = MathUtil.clamp(anchorY - height - 6, 6, canvas.getHeight() - height - 6);
        g.setFill(Color.color(0.97, 0.98, 0.99, 1.0));
        g.fillRoundRect(x, y, width, height, 8, 8);
        g.setStroke(Color.color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0.55));
        g.setLineWidth(0.8);
        g.strokeRoundRect(x, y, width, height, 8, 8);
        g.setFill(Color.color(0.15, 0.17, 0.20, 0.96));
        for (int index = 0; index < lines.size(); index++) {
            g.fillText(lines.get(index), x + 8, y + 14 + index * 11);
        }
    }

    private ContextMenu buildContextMenu(double mouseX, double mouseY) {
        var menu = new ContextMenu();
        if (overviewBounds().contains(mouseX, mouseY)) {
            var overview = new MenuItem("Overview");
            overview.setDisable(true);
            menu.getItems().addAll(overview, new SeparatorMenuItem(), overviewInteraction.newResetMenuItem());
            return menu;
        }
        if (populateCustomContextMenu(menu, mouseX, mouseY)) {
            menu.getItems().add(new SeparatorMenuItem());
            menu.getItems().add(overviewInteraction.newResetMenuItem());
            return menu;
        }
        if (hoveredTraceTarget != null) {
            var title = new MenuItem("Trace: " + hoveredTraceTarget.entry().name());
            title.setDisable(true);
            var select = new MenuItem("Select " + hoveredTraceTarget.entry().name());
            select.setOnAction(actionEvent -> paneContext.session().selection.setSingle(hoveredTraceTarget.entry().id()));
            var toggle = new MenuItem(hoveredTraceTarget.entry().visible() ? "Hide " + hoveredTraceTarget.entry().name() : "Show " + hoveredTraceTarget.entry().name());
            toggle.setOnAction(actionEvent -> paneContext.session().points.toggleVisibility(hoveredTraceTarget.entry().id()));
            var setCursor = new MenuItem("Set cursor here");
            setCursor.setOnAction(actionEvent -> moveCursor(mouseX));
            menu.getItems().addAll(title, new SeparatorMenuItem(), select, toggle, setCursor, new SeparatorMenuItem(), overviewInteraction.newResetMenuItem());
            return menu;
        }
        var setCursor = new MenuItem("Set cursor here");
        setCursor.setOnAction(actionEvent -> moveCursor(mouseX));
        menu.getItems().addAll(setCursor, new SeparatorMenuItem(), overviewInteraction.newResetMenuItem());
        return menu;
    }

    private TraceHoverTarget findHoveredTraceTarget(
        double mouseX,
        double mouseY,
        double tMin,
        double tMax,
        double plotWidth,
        double plotHeight
    ) {
        var referenceTrajectory = paneContext.session().reference.enabled.get()
            ? paneContext.session().reference.trajectory.get()
            : null;
        TraceHoverTarget best = null;
        double bestDistanceSq = 9.0 * 9.0;
        double tSpan = Math.max(1.0, tMax - tMin);
        for (var entry : paneContext.session().points.entries) {
            if (!entry.visible() || entry.trajectory() == null) continue;
            var state = entry.trajectory().interpolateAt(hoveredTimeMicros);
            if (state == null) continue;
            var rotated = ReferenceFrameUtil.rotateIntoReferenceFrame(state, referenceTrajectory, hoveredTimeMicros);
            double value = evalPlot(rotated.mx(), rotated.my(), rotated.mz());
            if (Double.isNaN(value)) continue;
            double x = xForTime(hoveredTimeMicros, tMin, tSpan, plotWidth);
            double y = yForValue(value, plotHeight);
            double dx = mouseX - x;
            double dy = mouseY - y;
            double distanceSq = dx * dx + dy * dy;
            if (distanceSq <= bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = new TraceHoverTarget(
                    entry,
                    hoveredTimeMicros,
                    value,
                    x,
                    y,
                    rotated.mx(),
                    rotated.my(),
                    rotated.mz()
                );
            }
        }
        return best;
    }

    private void drawTraceHoverOverlay(GraphicsContext g) {
        if (hoveredTraceTarget == null) return;
        g.setStroke(Color.color(
            hoveredTraceTarget.entry().colour().getRed(),
            hoveredTraceTarget.entry().colour().getGreen(),
            hoveredTraceTarget.entry().colour().getBlue(),
            0.95
        ));
        g.setLineWidth(1.4);
        g.strokeOval(hoveredTraceTarget.x() - 5, hoveredTraceTarget.y() - 5, 10, 10);
        drawInfoCard(
            g,
            hoveredMouseX,
            hoveredMouseY,
            hoveredTraceTarget.entry().colour(),
            java.util.List.of(
                hoveredTraceTarget.entry().name(),
                String.format("t = %.1f \u03bcs", hoveredTraceTarget.timeMicros()),
                viewModel.title() + " = " + formatPlotValue(hoveredTraceTarget.value()),
                String.format("Mx = %.3f, My = %.3f", hoveredTraceTarget.mx(), hoveredTraceTarget.my()),
                String.format("Mz = %.3f", hoveredTraceTarget.mz()),
                String.format("r = %.1f mm, z = %.1f mm", hoveredTraceTarget.entry().r(), hoveredTraceTarget.entry().z())
            )
        );
    }

    private String formatPlotValue(double value) {
        return "\u00b0".equals(viewModel.unit())
            ? String.format("%.1f%s", value, viewModel.unit())
            : String.format("%.3f%s", value, viewModel.unit());
    }

    private AxisScrubBar.Bounds overviewBounds() {
        return overviewBounds(canvas.getWidth());
    }

    private static AxisScrubBar.Bounds overviewBounds(double width) {
        return new AxisScrubBar.Bounds(PAD_LEFT, 2, Math.max(1, width - PAD_LEFT - PAD_RIGHT), OVERVIEW_H);
    }
}
