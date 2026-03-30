package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.ui.model.IsochromatEntry;
import ax.xz.mri.ui.theme.StudioTheme;
import ax.xz.mri.ui.viewmodel.TracePlotViewModel;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.CanvasWorkbenchPane;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
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
    private static final int DRAG_WINDOW_START = 1;
    private static final int DRAG_WINDOW_END = 2;
    private static final int DRAG_WINDOW = 3;
    private static final double OVERVIEW_H = 10;
    private static final double OVERVIEW_GAP = 6;

    private final TracePlotViewModel viewModel;
    private double dragStartX;
    private double dragStartTS;
    private double dragStartTE;
    private int dragMode;

    protected AbstractTracePlotPane(PaneContext paneContext, TracePlotViewModel viewModel) {
        super(paneContext);
        this.viewModel = viewModel;
        setPaneTitle(viewModel.title());
        bindRedraw(
            paneContext.session().points.entries,
            paneContext.session().selection.selectedIds,
            paneContext.session().viewport.tS,
            paneContext.session().viewport.tE,
            paneContext.session().viewport.tC,
            paneContext.session().viewport.maxTime,
            paneContext.session().document.currentPulse,
            paneContext.session().document.blochData
        );

        canvas.setOnMousePressed(event -> {
            if (!event.isPrimaryButtonDown()) return;
            dragStartTS = paneContext.session().viewport.tS.get();
            dragStartTE = paneContext.session().viewport.tE.get();
            dragStartX = event.getX();
            var overview = overviewBounds();
            if (overview.contains(event.getX(), event.getY())) {
                double maxTime = Math.max(paneContext.session().viewport.maxTime.get(), 1);
                double start = OverviewStrip.pixelAt(overview, dragStartTS, 0, maxTime);
                double end = OverviewStrip.pixelAt(overview, dragStartTE, 0, maxTime);
                if (Math.abs(event.getX() - start) <= OverviewStrip.HANDLE_HIT_RADIUS) {
                    dragMode = DRAG_WINDOW_START;
                } else if (Math.abs(event.getX() - end) <= OverviewStrip.HANDLE_HIT_RADIUS) {
                    dragMode = DRAG_WINDOW_END;
                } else {
                    if (event.getX() < start || event.getX() > end) {
                        double span = dragStartTE - dragStartTS;
                        double centre = OverviewStrip.valueAt(overview, event.getX(), 0, maxTime);
                        paneContext.session().viewport.setAnalysisWindow(centre - span / 2, centre + span / 2);
                        dragStartTS = paneContext.session().viewport.tS.get();
                        dragStartTE = paneContext.session().viewport.tE.get();
                    }
                    dragMode = DRAG_WINDOW;
                }
                return;
            }
            moveCursor(event.getX());
        });
        canvas.setOnMouseDragged(event -> {
            if (dragMode == DRAG_WINDOW_START) {
                paneContext.session().viewport.moveAnalysisStart(
                    OverviewStrip.valueAt(overviewBounds(), event.getX(), 0, Math.max(paneContext.session().viewport.maxTime.get(), 1))
                );
                return;
            }
            if (dragMode == DRAG_WINDOW_END) {
                paneContext.session().viewport.moveAnalysisEnd(
                    OverviewStrip.valueAt(overviewBounds(), event.getX(), 0, Math.max(paneContext.session().viewport.maxTime.get(), 1))
                );
                return;
            }
            if (dragMode == DRAG_WINDOW) {
                double maxTime = Math.max(paneContext.session().viewport.maxTime.get(), 1);
                double delta = OverviewStrip.valueAt(overviewBounds(), event.getX(), 0, maxTime)
                    - OverviewStrip.valueAt(overviewBounds(), dragStartX, 0, maxTime);
                paneContext.session().viewport.setAnalysisWindow(dragStartTS + delta, dragStartTE + delta);
                return;
            }
            moveCursor(event.getX());
        });
        canvas.setOnMouseReleased(event -> dragMode = 0);
        canvas.setOnMouseMoved(event -> updateStatus(event.getX()));
        canvas.setOnContextMenuRequested(event -> {
            var menu = new ContextMenu();
            var setCursor = new MenuItem("Set cursor here");
            setCursor.setOnAction(actionEvent -> moveCursor(event.getX()));
            menu.getItems().add(setCursor);
            showCanvasContextMenu(menu, event.getScreenX(), event.getScreenY());
        });
    }

    @Override
    protected void paint(GraphicsContext g, double width, double height) {
        g.setFill(BG);
        g.fillRect(0, 0, width, height);

        double padLeft = 40;
        double padRight = 8;
        double padTop = 14 + OVERVIEW_H + OVERVIEW_GAP;
        double padBottom = 18;
        double plotWidth = width - padLeft - padRight;
        double plotHeight = height - padTop - padBottom;
        double tMin = paneContext.session().viewport.tS.get();
        double tMax = Math.max(paneContext.session().viewport.tE.get(), tMin + 1);
        double tSpan = tMax - tMin;
        double cursorTime = paneContext.session().viewport.tC.get();
        OverviewStrip.drawHorizontal(
            g,
            overviewBounds(width),
            0,
            Math.max(paneContext.session().viewport.maxTime.get(), 1),
            tMin,
            tMax,
            OverviewStrip.rfSpans(
                paneContext.session().document.blochData.get(),
                paneContext.session().document.currentPulse.get(),
                Color.web("#1565c0"),
                0.20
            ),
            cursorTime
        );

        g.setTextAlign(TextAlignment.RIGHT);
        for (double tick : viewModel.ticks()) {
            double y = padTop + plotHeight - (tick - viewModel.min()) / (viewModel.max() - viewModel.min()) * plotHeight;
            g.setStroke(Color.color(0, 0, 0, tick == 0 ? 0.15 : 0.06));
            g.setLineWidth(tick == 0 ? 0.6 : 0.3);
            g.strokeLine(padLeft, y, padLeft + plotWidth, y);
            g.setFill(TX);
            g.setFont(UI_8);
            String label = "\u00b0".equals(viewModel.unit()) ? (int) tick + "\u00b0"
                : (tick % 1 != 0 ? String.format("%.2f", tick) : String.valueOf((int) tick));
            g.fillText(label, padLeft - 4, y + 3);
        }
        g.setTextAlign(TextAlignment.LEFT);

        g.setStroke(Color.color(0, 0, 0, 0.15));
        g.setLineWidth(0.5);
        g.beginPath();
        g.moveTo(padLeft, padTop);
        g.lineTo(padLeft, padTop + plotHeight);
        g.lineTo(padLeft + plotWidth, padTop + plotHeight);
        g.stroke();

        int xTickStep = niceTick(tSpan);
        g.setFill(TX2);
        g.setFont(UI_7);
        g.setTextAlign(TextAlignment.CENTER);
        g.setGlobalAlpha(0.6);
        for (double tick = Math.ceil(tMin / xTickStep) * xTickStep; tick <= tMax; tick += xTickStep) {
            double x = padLeft + (tick - tMin) / tSpan * plotWidth;
            if (x > padLeft + 4 && x < padLeft + plotWidth - 4) {
                String label = tSpan > 2000
                    ? String.format("%.0f", tick / 1000) + (tick % 1000 != 0 ? String.format(".%01.0f", (tick % 1000) / 100) : "")
                    : String.valueOf((int) tick);
                g.fillText(label, x, padTop + plotHeight + 10);
                g.setStroke(Color.color(0, 0, 0, 0.04));
                g.setLineWidth(0.3);
                g.strokeLine(x, padTop, x, padTop + plotHeight);
            }
        }
        if (!viewModel.unit().isEmpty()) {
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(tSpan > 2000 ? "ms" : "\u03bcs", padLeft + plotWidth, padTop + plotHeight + 10);
            g.setTextAlign(TextAlignment.LEFT);
        }
        g.setGlobalAlpha(1);

        double cursorX = padLeft + (cursorTime - tMin) / tSpan * plotWidth;
        g.setStroke(CUR);
        g.setLineWidth(1);
        g.setGlobalAlpha(0.5);
        g.strokeLine(cursorX, padTop, cursorX, padTop + plotHeight);
        g.setGlobalAlpha(1);

        g.setFill(TX);
        g.setFont(UI_BOLD_10);
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(viewModel.title(), padLeft + plotWidth / 2, padTop - 3);
        g.setTextAlign(TextAlignment.LEFT);

        g.save();
        g.beginPath();
        g.rect(padLeft, padTop - 1, plotWidth, plotHeight + 2);
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
                double value = evalPlot(entry, pointIndex);
                if (Double.isNaN(value)) {
                    started = false;
                    continue;
                }
                double x = padLeft + (t - tMin) / tSpan * plotWidth;
                double y = padTop + plotHeight - (value - viewModel.min()) / (viewModel.max() - viewModel.min()) * plotHeight;
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
                double value = evalPlot(cursorState.mx(), cursorState.my(), cursorState.mz());
                if (!Double.isNaN(value)) {
                    double y = padTop + plotHeight - (value - viewModel.min()) / (viewModel.max() - viewModel.min()) * plotHeight;
                    g.setFill(entry.colour());
                    double radius = selected ? 4 : 3;
                    g.fillOval(cursorX - radius, y - radius, radius * 2, radius * 2);
                }
            }
        }
        g.restore();
    }

    private double evalPlot(IsochromatEntry entry, int pointIndex) {
        return evalPlot(
            entry.trajectory().mxAt(pointIndex),
            entry.trajectory().myAt(pointIndex),
            entry.trajectory().mzAt(pointIndex)
        );
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
        double plotWidth = canvas.getWidth() - 48;
        double time = tMin + (mouseX - 40) / plotWidth * (tMax - tMin);
        paneContext.session().viewport.setCursor(time);
    }

    private void updateStatus(double mouseX) {
        double tMin = paneContext.session().viewport.tS.get();
        double tMax = Math.max(paneContext.session().viewport.tE.get(), tMin + 1);
        double plotWidth = canvas.getWidth() - 48;
        double time = tMin + (mouseX - 40) / plotWidth * (tMax - tMin);
        long visible = paneContext.session().points.entries.stream()
            .filter(entry -> entry.visible() && entry.trajectory() != null)
            .count();
        setPaneStatus(String.format("t=%.1f \u03bcs | %d traces visible", time, visible));
    }

    private static int niceTick(double span) {
        return span > 5000 ? 2000 : span > 2000 ? 1000 : span > 800 ? 200 : span > 300 ? 100 : 50;
    }

    private OverviewStrip.Bounds overviewBounds() {
        return overviewBounds(canvas.getWidth());
    }

    private static OverviewStrip.Bounds overviewBounds(double width) {
        return new OverviewStrip.Bounds(40, 2, Math.max(1, width - 48), OVERVIEW_H);
    }
}
