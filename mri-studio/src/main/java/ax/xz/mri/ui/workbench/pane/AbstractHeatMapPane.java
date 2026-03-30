package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.simulation.PhaseMapData;
import ax.xz.mri.ui.canvas.ColorUtil;
import ax.xz.mri.ui.theme.StudioTheme;
import ax.xz.mri.ui.viewmodel.HeatMapViewModel;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.CanvasWorkbenchPane;
import ax.xz.mri.util.MathUtil;
import javafx.beans.property.ObjectProperty;
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
import static ax.xz.mri.ui.theme.StudioTheme.UI_BOLD_9;

/** Shared single-heatmap pane. */
public abstract class AbstractHeatMapPane extends CanvasWorkbenchPane {
    private static final int DRAG_WINDOW_START = 1;
    private static final int DRAG_WINDOW_END = 2;
    private static final int DRAG_WINDOW = 3;
    private static final double OVERVIEW_H = 10;
    private static final double OVERVIEW_GAP = 6;

    private final HeatMapViewModel viewModel;
    private final ObjectProperty<PhaseMapData> dataProperty;
    private double dragStartX;
    private double dragStartTS;
    private double dragStartTE;
    private int dragMode;

    protected AbstractHeatMapPane(PaneContext paneContext, HeatMapViewModel viewModel,
                                  ObjectProperty<PhaseMapData> dataProperty) {
        super(paneContext);
        this.viewModel = viewModel;
        this.dataProperty = dataProperty;

        setPaneTitle(viewModel.title());
        bindRedraw(
            dataProperty,
            paneContext.session().viewport.tS,
            paneContext.session().viewport.tE,
            paneContext.session().viewport.tC,
            paneContext.session().viewport.maxTime,
            paneContext.session().document.blochData,
            paneContext.session().document.currentPulse
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
        canvas.setOnMouseMoved(event -> updateStatus(event.getX(), event.getY()));
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
        var phaseMap = dataProperty.get();
        if (phaseMap == null) return;

        double tMin = paneContext.session().viewport.tS.get();
        double tMax = Math.max(paneContext.session().viewport.tE.get(), tMin + 1);
        double tSpan = tMax - tMin;
        double padLeft = 34;
        double padRight = 4;
        double padTop = 14 + OVERVIEW_H + OVERVIEW_GAP;
        double padBottom = 16;
        double plotWidth = width - padLeft - padRight;
        double plotHeight = height - padTop - padBottom;
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
            paneContext.session().viewport.tC.get()
        );

        g.setFill(TX);
        g.setFont(UI_BOLD_9);
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(viewModel.title(), padLeft + plotWidth / 2, padTop - 3);
        g.setTextAlign(TextAlignment.LEFT);

        double yMin = phaseMap.yArr()[0];
        double yMax = phaseMap.yArr()[phaseMap.nY() - 1];
        g.setFont(UI_8);
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFill(TX);
        for (double tick : viewModel.ticks()) {
            double y = padTop + plotHeight * (1 - (tick - yMin) / (yMax - yMin));
            g.fillText(String.valueOf((int) tick), padLeft - 3, y + 2);
            g.setStroke(Color.color(0, 0, 0, 0.06));
            g.setLineWidth(0.3);
            g.strokeLine(padLeft, y, padLeft + plotWidth, y);
        }
        g.setTextAlign(TextAlignment.LEFT);

        double cellHeight = plotHeight / phaseMap.nY();
        for (int yIndex = 0; yIndex < phaseMap.nY(); yIndex++) {
            var row = phaseMap.data()[yIndex];
            double y = padTop + plotHeight - ((yIndex + 0.5) / phaseMap.nY()) * plotHeight;
            for (int tIndex = 0; tIndex < row.length; tIndex++) {
                var cell = row[tIndex];
                if (cell.tMicros() < tMin || cell.tMicros() > tMax) continue;
                double x = padLeft + (cell.tMicros() - tMin) / tSpan * plotWidth;
                double nextT = (tIndex + 1 < row.length) ? row[tIndex + 1].tMicros() : cell.tMicros() + 40;
                double cellWidth = Math.max(1, (nextT - cell.tMicros()) / tSpan * plotWidth + 1);
                g.setFill(ColorUtil.hue2color(cell.phaseDeg(), MathUtil.clamp(cell.mPerp(), 0, 1)));
                g.fillRect(x, y - cellHeight / 2, cellWidth, cellHeight + 1);
            }
        }

        if (viewModel.showSliceBounds() && paneContext.session().document.blochData.get() != null) {
            var field = paneContext.session().document.blochData.get().field();
            double sliceHalf = (field.sliceHalf != null ? field.sliceHalf : 0.005) * 1e3;
            for (double zValue : new double[]{-sliceHalf, sliceHalf}) {
                double y = padTop + plotHeight * (1 - (zValue - yMin) / (yMax - yMin));
                g.setStroke(Color.web("#2e7d32"));
                g.setGlobalAlpha(0.4);
                g.setLineWidth(0.5);
                g.setLineDashes(3, 3);
                g.strokeLine(padLeft, y, padLeft + plotWidth, y);
            }
            g.setLineDashes();
            g.setGlobalAlpha(1);
        }

        double cursorX = padLeft + (paneContext.session().viewport.tC.get() - tMin) / tSpan * plotWidth;
        g.setStroke(CUR);
        g.setLineWidth(1.5);
        g.setGlobalAlpha(0.8);
        g.strokeLine(cursorX, padTop, cursorX, padTop + plotHeight);
        g.setGlobalAlpha(1);
        g.setFill(CUR);
        g.setGlobalAlpha(0.7);
        g.fillPolygon(
            new double[]{cursorX, cursorX - 4, cursorX + 4},
            new double[]{padTop + plotHeight, padTop + plotHeight + 6, padTop + plotHeight + 6},
            3
        );
        g.setGlobalAlpha(1);

        int tickStep = niceTick(tSpan);
        g.setFill(TX2);
        g.setFont(UI_7);
        g.setTextAlign(TextAlignment.CENTER);
        g.setGlobalAlpha(0.6);
        for (double tick = Math.ceil(tMin / tickStep) * tickStep; tick <= tMax; tick += tickStep) {
            double x = padLeft + (tick - tMin) / tSpan * plotWidth;
            if (x > padLeft + 4 && x < padLeft + plotWidth - 4) {
                String label = tSpan > 2000 ? String.format("%.0fms", tick / 1000) : (int) tick + "\u03bcs";
                g.fillText(label, x, height - 2);
            }
        }
        g.setTextAlign(TextAlignment.LEFT);
        g.setGlobalAlpha(1);
    }

    private void moveCursor(double mouseX) {
        double tMin = paneContext.session().viewport.tS.get();
        double tMax = Math.max(paneContext.session().viewport.tE.get(), tMin + 1);
        double plotWidth = canvas.getWidth() - 38;
        double time = tMin + (mouseX - 34) / plotWidth * (tMax - tMin);
        paneContext.session().viewport.setCursor(time);
    }

    private void updateStatus(double mouseX, double mouseY) {
        var phaseMap = dataProperty.get();
        if (phaseMap == null) {
            setPaneStatus("No phase data");
            return;
        }
        double tMin = paneContext.session().viewport.tS.get();
        double tMax = Math.max(paneContext.session().viewport.tE.get(), tMin + 1);
        double plotWidth = canvas.getWidth() - 38;
        double plotHeight = canvas.getHeight() - 30;
        double time = tMin + (mouseX - 34) / plotWidth * (tMax - tMin);
        double yMin = phaseMap.yArr()[0];
        double yMax = phaseMap.yArr()[phaseMap.nY() - 1];
        double yValue = yMax - ((mouseY - 14) / plotHeight) * (yMax - yMin);
        setPaneStatus(String.format("t=%.1f \u03bcs | y=%.2f", time, yValue));
    }

    private static int niceTick(double span) {
        return span > 5000 ? 2000 : span > 2000 ? 1000 : span > 800 ? 200 : span > 300 ? 100 : 50;
    }

    private OverviewStrip.Bounds overviewBounds() {
        return overviewBounds(canvas.getWidth());
    }

    private static OverviewStrip.Bounds overviewBounds(double width) {
        return new OverviewStrip.Bounds(34, 2, Math.max(1, width - 38), OVERVIEW_H);
    }
}
