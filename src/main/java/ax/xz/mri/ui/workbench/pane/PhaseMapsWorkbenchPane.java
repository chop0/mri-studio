package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.simulation.PhaseMapData;
import ax.xz.mri.ui.canvas.ColourUtil;
import ax.xz.mri.ui.theme.StudioTheme;
import ax.xz.mri.ui.viewmodel.HeatMapViewModel;
import ax.xz.mri.ui.viewmodel.ReferenceFrameUtil;
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
import static ax.xz.mri.ui.theme.StudioTheme.UI_BOLD_9;

/** Combined phase-map pane with shared timeline overview and side-by-side subplots. */
public class PhaseMapsWorkbenchPane extends CanvasWorkbenchPane {
    private static final double OVERVIEW_H = 10;
    private static final double OVERVIEW_GAP = 6;
    private static final double PAD_LEFT = 34;
    private static final double PAD_RIGHT = 6;
    private static final double PAD_TOP = 14 + OVERVIEW_H + OVERVIEW_GAP;
    private static final double PAD_BOTTOM = 16;
    private static final double PLOT_GAP = 12;

    private final AxisScrubBar.Interaction overviewInteraction;
    private int hoveredPlot = -1;
    private double hoveredTimeMicros;
    private double hoveredYValue;

    public PhaseMapsWorkbenchPane(PaneContext paneContext) {
        super(paneContext);
        setPaneTitle("Phase Maps");
        var viewport = paneContext.session().viewport;
        overviewInteraction = new AxisScrubBar.Interaction(
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

        bindRedraw(
            paneContext.session().derived.phaseMapZ,
            paneContext.session().derived.phaseMapR,
            paneContext.session().viewport.tS,
            paneContext.session().viewport.tE,
            paneContext.session().viewport.tC,
            paneContext.session().viewport.maxTime,
            paneContext.session().document.blochData,
            paneContext.session().document.currentPulse,
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
            var menu = new ContextMenu();
            if (overviewBounds().contains(event.getX(), event.getY())) {
                var overview = new MenuItem("Overview");
                overview.setDisable(true);
                menu.getItems().addAll(overview, new SeparatorMenuItem(), overviewInteraction.newResetMenuItem());
            } else {
                var setCursor = new MenuItem("Set cursor here");
                setCursor.setOnAction(actionEvent -> moveCursor(event.getX(), event.getY()));
                menu.getItems().addAll(setCursor, new SeparatorMenuItem(), overviewInteraction.newResetMenuItem());
            }
            showCanvasContextMenu(menu, event.getScreenX(), event.getScreenY());
        });
    }

    @Override
    protected void paint(GraphicsContext g, double width, double height) {
        g.setFill(BG);
        g.fillRect(0, 0, width, height);

        var leftData = paneContext.session().derived.phaseMapZ.get();
        var rightData = paneContext.session().derived.phaseMapR.get();
        if (leftData == null && rightData == null) {
            g.setFill(TX2);
            g.setFont(UI_BOLD_9);
            g.fillText("No phase-map data", PAD_LEFT, PAD_TOP + 18);
            return;
        }

        var referenceTrajectory = paneContext.session().reference.enabled.get()
            ? paneContext.session().reference.trajectory.get()
            : null;
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
        drawPhaseMapPlot(g, plots[0], paneContext.session().phaseMapZ, leftData, referenceTrajectory, hoveredPlot == 0);
        drawPhaseMapPlot(g, plots[1], paneContext.session().phaseMapR, rightData, referenceTrajectory, hoveredPlot == 1);
    }

    private void drawPhaseMapPlot(
        GraphicsContext g,
        PlotRect rect,
        HeatMapViewModel viewModel,
        PhaseMapData phaseMap,
        ax.xz.mri.model.simulation.Trajectory referenceTrajectory,
        boolean hovered
    ) {
        g.setFill(TX);
        g.setFont(UI_BOLD_9);
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(viewModel.title(), rect.x() + rect.width() / 2, rect.y() - 3);
        g.setTextAlign(TextAlignment.LEFT);

        if (phaseMap == null) {
            g.setFill(Color.color(0, 0, 0, 0.04));
            g.fillRoundRect(rect.x(), rect.y(), rect.width(), rect.height(), 8, 8);
            g.setFill(TX2);
            g.setFont(UI_8);
            g.fillText("Unavailable", rect.x() + 8, rect.y() + 16);
            return;
        }

        double tMin = paneContext.session().viewport.tS.get();
        double tMax = Math.max(paneContext.session().viewport.tE.get(), tMin + 1);
        double tSpan = tMax - tMin;
        double yMin = phaseMap.yArr()[0];
        double yMax = phaseMap.yArr()[phaseMap.nY() - 1];
        double[] referencePhaseOffsets = referencePhaseOffsets(phaseMap, referenceTrajectory);

        g.setFont(UI_8);
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFill(TX);
        for (double tick : viewModel.ticks()) {
            double y = rect.y() + rect.height() * (1 - (tick - yMin) / (yMax - yMin));
            g.fillText(String.valueOf((int) tick), rect.x() - 3, y + 2);
            g.setStroke(Color.color(0, 0, 0, 0.06));
            g.setLineWidth(0.3);
            g.strokeLine(rect.x(), y, rect.x() + rect.width(), y);
        }
        g.setTextAlign(TextAlignment.LEFT);

        double cellHeight = rect.height() / phaseMap.nY();
        for (int yIndex = 0; yIndex < phaseMap.nY(); yIndex++) {
            var row = phaseMap.data()[yIndex];
            double y = rect.y() + rect.height() - ((yIndex + 0.5) / phaseMap.nY()) * rect.height();
            for (int tIndex = 0; tIndex < row.length; tIndex++) {
                var cell = row[tIndex];
                if (cell.tMicros() < tMin || cell.tMicros() > tMax) continue;
                double x = rect.x() + (cell.tMicros() - tMin) / tSpan * rect.width();
                double nextT = (tIndex + 1 < row.length) ? row[tIndex + 1].tMicros() : cell.tMicros() + 40;
                double cellWidth = Math.max(1, (nextT - cell.tMicros()) / tSpan * rect.width() + 1);
                double phaseDeg = referencePhaseOffsets == null || tIndex >= referencePhaseOffsets.length
                    ? cell.phaseDeg()
                    : ReferenceFrameUtil.normalizeDegrees(cell.phaseDeg() - referencePhaseOffsets[tIndex]);
                g.setFill(ColourUtil.hue2color(phaseDeg, MathUtil.clamp(cell.mPerp(), 0, 1)));
                g.fillRect(x, y - cellHeight / 2, cellWidth, cellHeight + 1);
            }
        }

        if (viewModel.showSliceBounds() && paneContext.session().document.blochData.get() != null) {
            var field = paneContext.session().document.blochData.get().field();
            double sliceHalf = (field.sliceHalf != null ? field.sliceHalf : 0.005) * 1e3;
            for (double zValue : new double[]{-sliceHalf, sliceHalf}) {
                double y = rect.y() + rect.height() * (1 - (zValue - yMin) / (yMax - yMin));
                g.setStroke(Color.web("#2e7d32"));
                g.setGlobalAlpha(0.4);
                g.setLineWidth(0.5);
                g.setLineDashes(3, 3);
                g.strokeLine(rect.x(), y, rect.x() + rect.width(), y);
            }
            g.setLineDashes();
            g.setGlobalAlpha(1);
        }

        double cursorX = rect.x() + (paneContext.session().viewport.tC.get() - tMin) / tSpan * rect.width();
        g.setStroke(CUR);
        g.setLineWidth(1.4);
        g.setGlobalAlpha(0.8);
        g.strokeLine(cursorX, rect.y(), cursorX, rect.y() + rect.height());
        g.setGlobalAlpha(1);

        int tickStep = niceTick(tSpan);
        g.setFill(TX2);
        g.setFont(UI_7);
        g.setTextAlign(TextAlignment.CENTER);
        g.setGlobalAlpha(0.6);
        for (double tick = Math.ceil(tMin / tickStep) * tickStep; tick <= tMax; tick += tickStep) {
            double x = rect.x() + (tick - tMin) / tSpan * rect.width();
            if (x > rect.x() + 4 && x < rect.x() + rect.width() - 4) {
                String label = tSpan > 2000 ? String.format("%.0fms", tick / 1000) : (int) tick + "\u03bcs";
                g.fillText(label, x, rect.y() + rect.height() + 10);
            }
        }
        g.setTextAlign(TextAlignment.LEFT);
        g.setGlobalAlpha(1);

        if (hovered) {
            double hoverX = rect.x() + (hoveredTimeMicros - tMin) / tSpan * rect.width();
            double hoverY = rect.y() + rect.height() * (1 - (hoveredYValue - yMin) / (yMax - yMin));
            g.setStroke(Color.color(StudioTheme.AC.getRed(), StudioTheme.AC.getGreen(), StudioTheme.AC.getBlue(), 0.35));
            g.setLineWidth(0.8);
            g.setLineDashes(4, 3);
            g.strokeLine(hoverX, rect.y(), hoverX, rect.y() + rect.height());
            g.strokeLine(rect.x(), hoverY, rect.x() + rect.width(), hoverY);
            g.setLineDashes();
        }
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
        int nextPlot = plotIndexAt(mouseX, mouseY);
        if (nextPlot < 0) {
            if (hoveredPlot != -1) {
                hoveredPlot = -1;
                scheduleRedraw();
            }
            return;
        }
        var rect = plotRects(canvas.getWidth(), canvas.getHeight())[nextPlot];
        var data = nextPlot == 0 ? paneContext.session().derived.phaseMapZ.get() : paneContext.session().derived.phaseMapR.get();
        if (data == null) {
            if (hoveredPlot != -1) {
                hoveredPlot = -1;
                scheduleRedraw();
            }
            return;
        }
        double tMin = paneContext.session().viewport.tS.get();
        double tMax = Math.max(paneContext.session().viewport.tE.get(), tMin + 1);
        hoveredTimeMicros = tMin + (mouseX - rect.x()) / Math.max(1, rect.width()) * (tMax - tMin);
        double yMin = data.yArr()[0];
        double yMax = data.yArr()[data.nY() - 1];
        hoveredYValue = yMax - ((mouseY - rect.y()) / Math.max(1, rect.height())) * (yMax - yMin);
        if (hoveredPlot != nextPlot) {
            hoveredPlot = nextPlot;
        }
        scheduleRedraw();
    }

    private void updateStatus() {
        if (hoveredPlot < 0) {
            setPaneStatus(String.format(
                "analysis=[%.1f, %.1f] \u03bcs | cursor=%.1f \u03bcs",
                paneContext.session().viewport.tS.get(),
                paneContext.session().viewport.tE.get(),
                paneContext.session().viewport.tC.get()
            ));
            return;
        }
        String label = hoveredPlot == 0 ? paneContext.session().phaseMapZ.title() : paneContext.session().phaseMapR.title();
        setPaneStatus(String.format("%s | t=%.1f \u03bcs | y=%.2f", label, hoveredTimeMicros, hoveredYValue));
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

    private static double[] referencePhaseOffsets(
        PhaseMapData phaseMap,
        ax.xz.mri.model.simulation.Trajectory referenceTrajectory
    ) {
        if (phaseMap == null || referenceTrajectory == null || phaseMap.data().length == 0) return null;
        var row = phaseMap.data()[0];
        var offsets = new double[row.length];
        for (int index = 0; index < row.length; index++) {
            var referenceState = referenceTrajectory.interpolateAt(row[index].tMicros());
            offsets[index] = referenceState != null ? referenceState.phaseDeg() : 0.0;
        }
        return offsets;
    }

    private record PlotRect(double x, double y, double width, double height) {
        boolean contains(double px, double py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }
    }
}
