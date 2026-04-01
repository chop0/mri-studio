package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.ui.model.IsochromatEntry;
import ax.xz.mri.ui.theme.StudioTheme;
import ax.xz.mri.ui.viewmodel.ReferenceFrameUtil;
import ax.xz.mri.ui.viewmodel.TracePlotViewModel;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.CanvasWorkbenchPane;
import ax.xz.mri.util.MathUtil;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

import java.util.List;

import static ax.xz.mri.ui.theme.StudioTheme.BG;
import static ax.xz.mri.ui.theme.StudioTheme.CUR;
import static ax.xz.mri.ui.theme.StudioTheme.TX;
import static ax.xz.mri.ui.theme.StudioTheme.TX2;
import static ax.xz.mri.ui.theme.StudioTheme.UI_7;
import static ax.xz.mri.ui.theme.StudioTheme.UI_8;
import static ax.xz.mri.ui.theme.StudioTheme.UI_BOLD_10;

/** Combined angular trace pane containing phase and polar traces side-by-side. */
public class PhaseTracesWorkbenchPane extends CanvasWorkbenchPane {
    private static final double OVERVIEW_H = 10;
    private static final double OVERVIEW_GAP = 6;
    private static final double LEGEND_H = 16;
    private static final double TITLE_H = 14;
    private static final double PAD_LEFT = 40;
    private static final double PAD_RIGHT = 8;
    private static final double PAD_TOP = 14 + OVERVIEW_H + OVERVIEW_GAP + LEGEND_H + TITLE_H;
    private static final double PAD_BOTTOM = 18;
    private static final double PLOT_GAP = 14;

    private record HoverTarget(
        IsochromatEntry entry,
        int plotIndex,
        double timeMicros,
        double value,
        double x,
        double y,
        double mx,
        double my,
        double mz
    ) {
    }

    private final AxisScrubBar.Interaction overviewInteraction;
    private int hoveredPlot = -1;
    private double hoveredTimeMicros;
    private double hoveredMouseX;
    private double hoveredMouseY;
    private HoverTarget hoveredTarget;

    public PhaseTracesWorkbenchPane(PaneContext paneContext) {
        super(paneContext);
        setPaneTitle("Phase Traces");
        overviewInteraction = new AxisScrubBar.Interaction(
            AxisScrubBar.Orientation.HORIZONTAL,
            new AxisScrubBar.WindowModel() {
                @Override
                public double domainStart() {
                    return 0;
                }

                @Override
                public double domainEnd() {
                    return Math.max(paneContext.session().viewport.maxTime.get(), 1);
                }

                @Override
                public double windowStart() {
                    return paneContext.session().viewport.tS.get();
                }

                @Override
                public double windowEnd() {
                    return paneContext.session().viewport.tE.get();
                }

                @Override
                public void setWindow(double start, double end) {
                    paneContext.session().viewport.setAnalysisWindow(start, end);
                }

                @Override
                public void zoomAround(double anchor, double factor) {
                    paneContext.session().viewport.zoomAnalysisWindowAround(anchor, factor);
                }

                @Override
                public void resetWindow() {
                    paneContext.session().viewport.fitAnalysisToData();
                }
            }
        );

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
                updateStatus();
                scheduleRedraw();
                return;
            }
            moveCursor(event.getX(), event.getY());
        });
        canvas.setOnMouseDragged(event -> {
            if (overviewInteraction.handleDrag(overviewBounds(), event)) {
                updateHover(event.getX(), event.getY());
                updateStatus();
                scheduleRedraw();
                return;
            }
            moveCursor(event.getX(), event.getY());
        });
        canvas.setOnMouseReleased(event -> overviewInteraction.handleRelease());
        canvas.setOnMouseMoved(event -> {
            updateHover(event.getX(), event.getY());
            updateStatus();
        });
        canvas.setOnMouseExited(event -> {
            hoveredPlot = -1;
            scheduleRedraw();
        });
        canvas.setOnScroll(event -> {
            if (overviewInteraction.handleScroll(overviewBounds(), event)) {
                updateStatus();
                scheduleRedraw();
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

        AxisScrubBar.draw(
            g,
            overviewBounds(width),
            AxisScrubBar.Spec.horizontal(
                0,
                Math.max(paneContext.session().viewport.maxTime.get(), 1),
                paneContext.session().viewport.tS.get(),
                paneContext.session().viewport.tE.get(),
                AxisScrubBar.rfSpans(
                    paneContext.session().document.blochData.get(),
                    paneContext.session().document.currentPulse.get(),
                    Color.web("#1565c0"),
                    0.20
                ),
                List.of(new AxisScrubBar.Marker(paneContext.session().viewport.tC.get(), CUR, 0.85, 1.0))
            )
        );

        var plots = plotRects(width, height);
        var referenceTrajectory = paneContext.session().reference.enabled.get()
            ? paneContext.session().reference.trajectory.get()
            : null;
        drawTracePlot(g, plots[0], paneContext.session().tracePhase, referenceTrajectory, hoveredPlot == 0);
        drawTracePlot(g, plots[1], paneContext.session().tracePolar, referenceTrajectory, hoveredPlot == 1);
        drawLegend(g, width);
        drawHoverOverlay(g);
    }

    private void drawTracePlot(
        GraphicsContext g,
        PlotRect rect,
        TracePlotViewModel viewModel,
        ax.xz.mri.model.simulation.Trajectory referenceTrajectory,
        boolean hovered
    ) {
        double tMin = paneContext.session().viewport.tS.get();
        double tMax = Math.max(paneContext.session().viewport.tE.get(), tMin + 1);
        double tSpan = tMax - tMin;
        double cursorTime = paneContext.session().viewport.tC.get();

        g.setTextAlign(TextAlignment.RIGHT);
        for (double tick : viewModel.ticks()) {
            double y = rect.y() + rect.height() - (tick - viewModel.min()) / (viewModel.max() - viewModel.min()) * rect.height();
            g.setStroke(Color.color(0, 0, 0, tick == 0 ? 0.15 : 0.06));
            g.setLineWidth(tick == 0 ? 0.6 : 0.3);
            g.strokeLine(rect.x(), y, rect.x() + rect.width(), y);
            g.setFill(TX);
            g.setFont(UI_8);
            String label = "\u00b0".equals(viewModel.unit()) ? (int) tick + "\u00b0"
                : (tick % 1 != 0 ? String.format("%.2f", tick) : String.valueOf((int) tick));
            g.fillText(label, rect.x() - 4, y + 3);
        }
        g.setTextAlign(TextAlignment.LEFT);

        g.setStroke(Color.color(0, 0, 0, 0.15));
        g.setLineWidth(0.5);
        g.beginPath();
        g.moveTo(rect.x(), rect.y());
        g.lineTo(rect.x(), rect.y() + rect.height());
        g.lineTo(rect.x() + rect.width(), rect.y() + rect.height());
        g.stroke();

        int xTickStep = niceTick(tSpan);
        g.setFill(TX2);
        g.setFont(UI_7);
        g.setTextAlign(TextAlignment.CENTER);
        g.setGlobalAlpha(0.6);
        for (double tick = Math.ceil(tMin / xTickStep) * xTickStep; tick <= tMax; tick += xTickStep) {
            double x = rect.x() + (tick - tMin) / tSpan * rect.width();
            if (x > rect.x() + 4 && x < rect.x() + rect.width() - 4) {
                String label = tSpan > 2000
                    ? String.format("%.0f", tick / 1000) + (tick % 1000 != 0 ? String.format(".%01.0f", (tick % 1000) / 100) : "")
                    : String.valueOf((int) tick);
                g.fillText(label, x, rect.y() + rect.height() + 10);
                g.setStroke(Color.color(0, 0, 0, 0.04));
                g.setLineWidth(0.3);
                g.strokeLine(x, rect.y(), x, rect.y() + rect.height());
            }
        }
        if (!viewModel.unit().isEmpty()) {
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(tSpan > 2000 ? "ms" : "\u03bcs", rect.x() + rect.width(), rect.y() + rect.height() + 10);
            g.setTextAlign(TextAlignment.LEFT);
        }
        g.setGlobalAlpha(1);

        double cursorX = rect.x() + (cursorTime - tMin) / tSpan * rect.width();
        g.setStroke(CUR);
        g.setLineWidth(1);
        g.setGlobalAlpha(0.5);
        g.strokeLine(cursorX, rect.y(), cursorX, rect.y() + rect.height());
        g.setGlobalAlpha(1);

        g.setFill(TX);
        g.setFont(UI_BOLD_10);
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(viewModel.title(), rect.x() + rect.width() / 2, rect.y() - 4);
        g.setTextAlign(TextAlignment.LEFT);

        g.save();
        g.beginPath();
        g.rect(rect.x(), rect.y() - 1, rect.width(), rect.height() + 2);
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
                double value = evalPlot(viewModel.kind(), rotated.mx(), rotated.my(), rotated.mz());
                if (Double.isNaN(value)) {
                    started = false;
                    continue;
                }
                double x = rect.x() + (t - tMin) / tSpan * rect.width();
                double y = rect.y() + rect.height() - (value - viewModel.min()) / (viewModel.max() - viewModel.min()) * rect.height();
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
                double value = evalPlot(viewModel.kind(), rotatedState.mx(), rotatedState.my(), rotatedState.mz());
                if (!Double.isNaN(value)) {
                    double y = rect.y() + rect.height() - (value - viewModel.min()) / (viewModel.max() - viewModel.min()) * rect.height();
                    g.setFill(entry.colour());
                    double radius = selected ? 4 : 3;
                    g.fillOval(cursorX - radius, y - radius, radius * 2, radius * 2);
                }
            }
        }
        if (hovered) {
            double hoverX = rect.x() + (hoveredTimeMicros - tMin) / tSpan * rect.width();
            g.setStroke(Color.color(StudioTheme.AC.getRed(), StudioTheme.AC.getGreen(), StudioTheme.AC.getBlue(), 0.35));
            g.setLineWidth(0.8);
            g.setLineDashes(4, 3);
            g.strokeLine(hoverX, rect.y(), hoverX, rect.y() + rect.height());
            g.setLineDashes();
        }
        if (hoveredTarget != null && hoveredTarget.plotIndex() == (viewModel.kind() == TracePlotViewModel.PlotKind.PHASE ? 0 : 1)) {
            g.setStroke(Color.color(
                hoveredTarget.entry().colour().getRed(),
                hoveredTarget.entry().colour().getGreen(),
                hoveredTarget.entry().colour().getBlue(),
                0.95
            ));
            g.setLineWidth(1.4);
            g.strokeOval(hoveredTarget.x() - 5, hoveredTarget.y() - 5, 10, 10);
        }
        g.restore();
    }

    private void moveCursor(double mouseX, double mouseY) {
        int plotIndex = plotIndexAt(mouseX, mouseY);
        if (plotIndex < 0) return;
        var rect = plotRects(canvas.getWidth(), canvas.getHeight())[plotIndex];
        double tMin = paneContext.session().viewport.tS.get();
        double tMax = Math.max(paneContext.session().viewport.tE.get(), tMin + 1);
        double time = tMin + (mouseX - rect.x()) / Math.max(1, rect.width()) * (tMax - tMin);
        paneContext.session().viewport.setCursor(time);
    }

    private void updateHover(double mouseX, double mouseY) {
        int next = plotIndexAt(mouseX, mouseY);
        if (next < 0) {
            if (hoveredPlot != -1 || hoveredTarget != null) {
                hoveredPlot = -1;
                hoveredTarget = null;
                scheduleRedraw();
            }
            return;
        }
        var rect = plotRects(canvas.getWidth(), canvas.getHeight())[next];
        double tMin = paneContext.session().viewport.tS.get();
        double tMax = Math.max(paneContext.session().viewport.tE.get(), tMin + 1);
        hoveredMouseX = mouseX;
        hoveredMouseY = mouseY;
        hoveredTimeMicros = tMin + (mouseX - rect.x()) / Math.max(1, rect.width()) * (tMax - tMin);
        hoveredTarget = findHoveredTarget(next, mouseX, mouseY, rect, tMin, tMax);
        if (hoveredPlot != next) hoveredPlot = next;
        scheduleRedraw();
    }

    private void updateStatus() {
        long visible = paneContext.session().points.entries.stream()
            .filter(entry -> entry.visible() && entry.trajectory() != null)
            .count();
        if (hoveredTarget != null) {
            String label = hoveredTarget.plotIndex() == 0 ? paneContext.session().tracePhase.title() : paneContext.session().tracePolar.title();
            setPaneStatus(String.format(
                "%s | t=%.1f \u03bcs | %s=%s | M=(%.3f, %.3f, %.3f) | (r=%.1f mm, z=%.1f mm)",
                hoveredTarget.entry().name(),
                hoveredTarget.timeMicros(),
                label,
                formatPlotValue(label, hoveredTarget.value()),
                hoveredTarget.mx(),
                hoveredTarget.my(),
                hoveredTarget.mz(),
                hoveredTarget.entry().r(),
                hoveredTarget.entry().z()
            ));
            return;
        }
        if (hoveredPlot < 0) {
            setPaneStatus(String.format("cursor=%.1f \u03bcs | %d traces visible", paneContext.session().viewport.tC.get(), visible));
            return;
        }
        String label = hoveredPlot == 0 ? paneContext.session().tracePhase.title() : paneContext.session().tracePolar.title();
        setPaneStatus(String.format("%s | t=%.1f \u03bcs | %d traces visible", label, hoveredTimeMicros, visible));
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

    private static double evalPlot(TracePlotViewModel.PlotKind kind, double mx, double my, double mz) {
        return switch (kind) {
            case PHASE -> {
                double magnitude = Math.sqrt(mx * mx + my * my);
                yield magnitude > 0.01 ? Math.atan2(my, mx) * 180.0 / Math.PI : Double.NaN;
            }
            case POLAR -> Math.atan2(Math.sqrt(mx * mx + my * my), mz) * 180.0 / Math.PI;
            case MPERP -> Math.sqrt(mx * mx + my * my);
        };
    }

    private int plotIndexAt(double mouseX, double mouseY) {
        var plots = plotRects(canvas.getWidth(), canvas.getHeight());
        for (int i = 0; i < plots.length; i++) {
            if (plots[i].contains(mouseX, mouseY)) return i;
        }
        return -1;
    }

    private PlotRect[] plotRects(double width, double height) {
        double plotWidth = (width - PAD_LEFT - PAD_RIGHT - PLOT_GAP) / 2.0;
        double plotHeight = height - PAD_TOP - PAD_BOTTOM;
        return new PlotRect[]{
            new PlotRect(PAD_LEFT, PAD_TOP, Math.max(1, plotWidth), Math.max(1, plotHeight)),
            new PlotRect(PAD_LEFT + plotWidth + PLOT_GAP, PAD_TOP, Math.max(1, plotWidth), Math.max(1, plotHeight))
        };
    }

    private AxisScrubBar.Bounds overviewBounds() {
        return overviewBounds(canvas.getWidth());
    }

    private static AxisScrubBar.Bounds overviewBounds(double width) {
        return new AxisScrubBar.Bounds(PAD_LEFT, 2, Math.max(1, width - PAD_LEFT - PAD_RIGHT), OVERVIEW_H);
    }

    private static int niceTick(double span) {
        return span > 5000 ? 2000 : span > 2000 ? 1000 : span > 800 ? 200 : span > 300 ? 100 : 50;
    }

    private ContextMenu buildContextMenu(double mouseX, double mouseY) {
        var menu = new ContextMenu();
        if (overviewBounds().contains(mouseX, mouseY)) {
            var overview = new MenuItem("Overview");
            overview.setDisable(true);
            menu.getItems().addAll(overview, new SeparatorMenuItem(), overviewInteraction.newResetMenuItem());
            return menu;
        }
        if (hoveredTarget != null) {
            String label = hoveredTarget.plotIndex() == 0 ? paneContext.session().tracePhase.title() : paneContext.session().tracePolar.title();
            var title = new MenuItem(label + ": " + hoveredTarget.entry().name());
            title.setDisable(true);
            var select = new MenuItem("Select " + hoveredTarget.entry().name());
            select.setOnAction(actionEvent -> paneContext.session().selection.setSingle(hoveredTarget.entry().id()));
            var toggle = new MenuItem(hoveredTarget.entry().visible()
                ? "Hide " + hoveredTarget.entry().name()
                : "Show " + hoveredTarget.entry().name());
            toggle.setOnAction(actionEvent -> paneContext.session().points.toggleVisibility(hoveredTarget.entry().id()));
            var setCursor = new MenuItem("Set Cursor Here");
            setCursor.setOnAction(actionEvent -> paneContext.session().viewport.setCursor(hoveredTarget.timeMicros()));
            menu.getItems().addAll(title, new SeparatorMenuItem(), select, toggle, setCursor, new SeparatorMenuItem(), overviewInteraction.newResetMenuItem());
            return menu;
        }
        var setCursor = new MenuItem("Set Cursor Here");
        setCursor.setOnAction(actionEvent -> moveCursor(mouseX, mouseY));
        menu.getItems().addAll(setCursor, new SeparatorMenuItem(), overviewInteraction.newResetMenuItem());
        return menu;
    }

    private HoverTarget findHoveredTarget(int plotIndex, double mouseX, double mouseY, PlotRect rect, double tMin, double tMax) {
        var referenceTrajectory = paneContext.session().reference.enabled.get()
            ? paneContext.session().reference.trajectory.get()
            : null;
        var viewModel = plotIndex == 0 ? paneContext.session().tracePhase : paneContext.session().tracePolar;
        HoverTarget best = null;
        double bestDistanceSq = 9.0 * 9.0;
        double tSpan = Math.max(1.0, tMax - tMin);
        for (var entry : paneContext.session().points.entries) {
            if (!entry.visible() || entry.trajectory() == null) continue;
            var state = entry.trajectory().interpolateAt(hoveredTimeMicros);
            if (state == null) continue;
            var rotated = ReferenceFrameUtil.rotateIntoReferenceFrame(state, referenceTrajectory, hoveredTimeMicros);
            double value = evalPlot(viewModel.kind(), rotated.mx(), rotated.my(), rotated.mz());
            if (Double.isNaN(value)) continue;
            double x = rect.x() + (hoveredTimeMicros - tMin) / tSpan * rect.width();
            double y = rect.y() + rect.height() - (value - viewModel.min()) / (viewModel.max() - viewModel.min()) * rect.height();
            double dx = mouseX - x;
            double dy = mouseY - y;
            double distanceSq = dx * dx + dy * dy;
            if (distanceSq <= bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = new HoverTarget(
                    entry,
                    plotIndex,
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

    private void drawHoverOverlay(GraphicsContext g) {
        if (hoveredTarget == null) return;
        drawInfoCard(
            g,
            hoveredMouseX,
            hoveredMouseY,
            hoveredTarget.entry().colour(),
            List.of(
                hoveredTarget.entry().name(),
                String.format("t = %.1f \u03bcs", hoveredTarget.timeMicros()),
                String.format(
                    "%s = %s",
                    hoveredTarget.plotIndex() == 0 ? paneContext.session().tracePhase.title() : paneContext.session().tracePolar.title(),
                    formatPlotValue(hoveredTarget.plotIndex() == 0 ? paneContext.session().tracePhase.title() : paneContext.session().tracePolar.title(), hoveredTarget.value())
                ),
                String.format("Mx = %.3f, My = %.3f", hoveredTarget.mx(), hoveredTarget.my()),
                String.format("Mz = %.3f", hoveredTarget.mz()),
                String.format("r = %.1f mm, z = %.1f mm", hoveredTarget.entry().r(), hoveredTarget.entry().z())
            )
        );
    }

    private void drawInfoCard(GraphicsContext g, double anchorX, double anchorY, Color accent, List<String> lines) {
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

    private String formatPlotValue(String label, double value) {
        return label.contains("\u03c6") || label.contains("\u03b8")
            ? String.format("%.1f\u00b0", value)
            : String.format("%.3f", value);
    }

    private record PlotRect(double x, double y, double width, double height) {
        boolean contains(double px, double py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }
    }
}
