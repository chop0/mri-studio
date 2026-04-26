package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.PhaseMapData;
import ax.xz.mri.model.simulation.Trajectory;
import ax.xz.mri.ui.canvas.ColourUtil;
import ax.xz.mri.ui.theme.StudioTheme;
import ax.xz.mri.ui.viewmodel.HeatMapViewModel;
import ax.xz.mri.ui.viewmodel.MagnetisationColouringSupport;
import ax.xz.mri.ui.viewmodel.MagnetisationColouringViewModel;
import ax.xz.mri.ui.viewmodel.ReferenceFrameUtil;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.CanvasWorkbenchPane;
import ax.xz.mri.util.MathUtil;
import javafx.beans.property.ObjectProperty;
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
import static ax.xz.mri.ui.theme.StudioTheme.UI_BOLD_9;

/** Shared single-heatmap pane. */
public abstract class AbstractHeatMapPane extends CanvasWorkbenchPane {
    private static final double OVERVIEW_H = 10;
    private static final double OVERVIEW_GAP = 6;
    private static final double PAD_LEFT = 34;
    private static final double PAD_RIGHT = 4;
    private static final double PAD_TOP = 14 + OVERVIEW_H + OVERVIEW_GAP;
    private static final double PAD_BOTTOM = 16;

    private final HeatMapViewModel viewModel;
    private final ObjectProperty<PhaseMapData> dataProperty;
    private final AxisScrubBar.Interaction overviewInteraction;
    private boolean hoveringPlot;
    private double hoveredTimeMicros;
    private double hoveredYValue;

    protected AbstractHeatMapPane(PaneContext paneContext, HeatMapViewModel viewModel,
                                  ObjectProperty<PhaseMapData> dataProperty) {
        super(paneContext);
        this.viewModel = viewModel;
        this.dataProperty = dataProperty;
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
        setToolNodes(MagnetisationColouringControls.newMenuButton(paneContext.session().colouring));
        bindRedraw(
            dataProperty,
            paneContext.session().viewport.tS,
            paneContext.session().viewport.tE,
            paneContext.session().viewport.tC,
            paneContext.session().viewport.maxTime,
            paneContext.session().document.blochData,
            paneContext.session().document.currentPulse,
            paneContext.session().colouring.hueSource,
            paneContext.session().colouring.brightnessSource,
            paneContext.session().reference.enabled,
            paneContext.session().reference.trajectory
        );

        canvas.setOnMousePressed(event -> {
            if (!event.isPrimaryButtonDown()) return;
            if (overviewInteraction.handlePress(overviewBounds(), event)) {
                updateHover(event.getX(), event.getY());
                updateStatus(event.getX(), event.getY());
                scheduleRedraw();
                return;
            }
            moveCursor(event.getX());
        });
        canvas.setOnMouseDragged(event -> {
            if (overviewInteraction.handleDrag(overviewBounds(), event)) {
                updateHover(event.getX(), event.getY());
                updateStatus(event.getX(), event.getY());
                scheduleRedraw();
                return;
            }
            updateHover(event.getX(), event.getY());
            moveCursor(event.getX());
        });
        canvas.setOnMouseReleased(event -> overviewInteraction.handleRelease());
        canvas.setOnMouseMoved(event -> {
            updateHover(event.getX(), event.getY());
            updateStatus(event.getX(), event.getY());
        });
        canvas.setOnScroll(event -> {
            if (overviewInteraction.handleScroll(overviewBounds(), event)) {
                scheduleRedraw();
                updateStatus(event.getX(), event.getY());
            }
        });
        canvas.setOnContextMenuRequested(event -> {
            var menu = new ContextMenu();
            if (overviewBounds().contains(event.getX(), event.getY())) {
                var overview = new MenuItem("Overview");
                overview.setDisable(true);
                menu.getItems().addAll(
                    overview,
                    new SeparatorMenuItem(),
                    MagnetisationColouringControls.newMenu(paneContext.session().colouring),
                    new SeparatorMenuItem(),
                    overviewInteraction.newResetMenuItem()
                );
            } else {
                var setCursor = new MenuItem("Set cursor here");
                setCursor.setOnAction(actionEvent -> moveCursor(event.getX()));
                menu.getItems().addAll(
                    setCursor,
                    new SeparatorMenuItem(),
                    MagnetisationColouringControls.newMenu(paneContext.session().colouring),
                    new SeparatorMenuItem(),
                    overviewInteraction.newResetMenuItem()
                );
            }
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
        double plotWidth = width - PAD_LEFT - PAD_RIGHT;
        double plotHeight = height - PAD_TOP - PAD_BOTTOM;
        var colouring = paneContext.session().colouring;
        var referenceTrajectory = paneContext.session().reference.enabled.get()
            ? paneContext.session().reference.trajectory.get()
            : null;
        double[] referencePhaseOffsets = referencePhaseOffsets(phaseMap, referenceTrajectory);
        boolean[] signalProjectionAvailable = signalProjectionAvailability(
            phaseMap,
            paneContext.session().document.blochData.get(),
            paneContext.session().document.currentPulse.get()
        );
        AxisScrubBar.draw(
            g,
            overviewBounds(width),
            AxisScrubBar.Spec.horizontal(
                0,
                Math.max(paneContext.session().viewport.maxTime.get(), 1),
                tMin,
                tMax,
                rfSpans(),
                java.util.List.of(new AxisScrubBar.Marker(paneContext.session().viewport.tC.get(), CUR, 0.85, 1.0))
            )
        );

        double yMin = phaseMap.yArr()[0];
        double yMax = phaseMap.yArr()[phaseMap.nY() - 1];
        g.setFont(UI_8);
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFill(TX);
        for (double tick : viewModel.ticks()) {
            double y = PAD_TOP + plotHeight * (1 - (tick - yMin) / (yMax - yMin));
            g.fillText(String.valueOf((int) tick), PAD_LEFT - 3, y + 2);
            g.setStroke(Color.color(0, 0, 0, 0.06));
            g.setLineWidth(0.3);
            g.strokeLine(PAD_LEFT, y, PAD_LEFT + plotWidth, y);
        }
        g.setTextAlign(TextAlignment.LEFT);

        double cellHeight = plotHeight / phaseMap.nY();
        for (int yIndex = 0; yIndex < phaseMap.nY(); yIndex++) {
            var row = phaseMap.data()[yIndex];
            double y = PAD_TOP + plotHeight - ((yIndex + 0.5) / phaseMap.nY()) * plotHeight;
            for (int tIndex = 0; tIndex < row.length; tIndex++) {
                var cell = row[tIndex];
                if (cell.tMicros() < tMin || cell.tMicros() > tMax) continue;
                double x = PAD_LEFT + (cell.tMicros() - tMin) / tSpan * plotWidth;
                double nextT = (tIndex + 1 < row.length) ? row[tIndex + 1].tMicros() : cell.tMicros() + 40;
                double cellWidth = Math.max(1, (nextT - cell.tMicros()) / tSpan * plotWidth + 1);
                double phaseDeg = referencePhaseOffsets == null || tIndex >= referencePhaseOffsets.length
                    ? cell.phaseDeg()
                    : ReferenceFrameUtil.normalizeDegrees(cell.phaseDeg() - referencePhaseOffsets[tIndex]);
                Color fill = cellColour(
                    colouring,
                    phaseDeg,
                    cell.mPerp(),
                    cell.signalProjection(),
                    tIndex < signalProjectionAvailable.length && signalProjectionAvailable[tIndex]
                );
                if (fill == null) continue;
                g.setFill(fill);
                g.fillRect(x, y - cellHeight / 2, cellWidth, cellHeight + 1);
            }
        }

        if (viewModel.showSliceBounds() && paneContext.session().document.blochData.get() != null) {
            var field = paneContext.session().document.blochData.get().field();
            double sliceHalf = (field.sliceHalf != null ? field.sliceHalf : 0.005) * 1e3;
            for (double zValue : new double[]{-sliceHalf, sliceHalf}) {
                double y = PAD_TOP + plotHeight * (1 - (zValue - yMin) / (yMax - yMin));
                g.setStroke(Color.web("#2e7d32"));
                g.setGlobalAlpha(0.4);
                g.setLineWidth(0.5);
                g.setLineDashes(3, 3);
                g.strokeLine(PAD_LEFT, y, PAD_LEFT + plotWidth, y);
            }
            g.setLineDashes();
            g.setGlobalAlpha(1);
        }

        double cursorX = PAD_LEFT + (paneContext.session().viewport.tC.get() - tMin) / tSpan * plotWidth;
        g.setStroke(CUR);
        g.setLineWidth(1.5);
        g.setGlobalAlpha(0.8);
        g.strokeLine(cursorX, PAD_TOP, cursorX, PAD_TOP + plotHeight);
        g.setGlobalAlpha(1);
        g.setFill(CUR);
        g.setGlobalAlpha(0.7);
        g.fillPolygon(
            new double[]{cursorX, cursorX - 4, cursorX + 4},
            new double[]{PAD_TOP + plotHeight, PAD_TOP + plotHeight + 6, PAD_TOP + plotHeight + 6},
            3
        );
        g.setGlobalAlpha(1);
        drawBadge(g, cursorX, PAD_TOP + 4, formatTime(paneContext.session().viewport.tC.get()), CUR);

        int tickStep = niceTick(tSpan);
        g.setFill(TX2);
        g.setFont(UI_7);
        g.setTextAlign(TextAlignment.CENTER);
        g.setGlobalAlpha(0.6);
        for (double tick = Math.ceil(tMin / tickStep) * tickStep; tick <= tMax; tick += tickStep) {
            double x = PAD_LEFT + (tick - tMin) / tSpan * plotWidth;
            if (x > PAD_LEFT + 4 && x < PAD_LEFT + plotWidth - 4) {
                String label = tSpan > 2000 ? String.format("%.0fms", tick / 1000) : (int) tick + "\u03bcs";
                g.fillText(label, x, height - 2);
            }
        }
        g.setTextAlign(TextAlignment.LEFT);
        g.setGlobalAlpha(1);

        if (hoveringPlot) {
            double hoverX = PAD_LEFT + (hoveredTimeMicros - tMin) / tSpan * plotWidth;
            double hoverY = PAD_TOP + plotHeight * (1 - (hoveredYValue - yMin) / (yMax - yMin));
            g.setStroke(Color.color(StudioTheme.AC.getRed(), StudioTheme.AC.getGreen(), StudioTheme.AC.getBlue(), 0.35));
            g.setLineWidth(0.8);
            g.setLineDashes(4, 3);
            g.strokeLine(hoverX, PAD_TOP, hoverX, PAD_TOP + plotHeight);
            g.strokeLine(PAD_LEFT, hoverY, PAD_LEFT + plotWidth, hoverY);
            g.setLineDashes();
            drawBadge(g, hoverX, PAD_TOP + plotHeight - 18, String.format("t=%.0f \u03bcs", hoveredTimeMicros), StudioTheme.AC);
        }
    }

    private void moveCursor(double mouseX) {
        double tMin = paneContext.session().viewport.tS.get();
        double tMax = Math.max(paneContext.session().viewport.tE.get(), tMin + 1);
        double plotWidth = canvas.getWidth() - PAD_LEFT - PAD_RIGHT;
        double time = tMin + (mouseX - PAD_LEFT) / plotWidth * (tMax - tMin);
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
        double plotWidth = canvas.getWidth() - PAD_LEFT - PAD_RIGHT;
        double plotHeight = canvas.getHeight() - PAD_TOP - PAD_BOTTOM;
        double time = tMin + (mouseX - PAD_LEFT) / plotWidth * (tMax - tMin);
        double yMin = phaseMap.yArr()[0];
        double yMax = phaseMap.yArr()[phaseMap.nY() - 1];
        double yValue = yMax - ((mouseY - PAD_TOP) / plotHeight) * (yMax - yMin);
        String fallback = paneContext.session().colouring.brightnessSource.get() ==
            MagnetisationColouringViewModel.BrightnessSource.SIGNAL_PROJECTION
            ? " | RF windows use excitation brightness"
            : "";
        setPaneStatus(String.format(
            "t=%.1f \u03bcs | y=%.2f | %s%s",
            time,
            yValue,
            paneContext.session().colouring.statusLabel(),
            fallback
        ));
    }

    private static int niceTick(double span) {
        return span > 5000 ? 2000 : span > 2000 ? 1000 : span > 800 ? 200 : span > 300 ? 100 : 50;
    }

    private void updateHover(double mouseX, double mouseY) {
        var phaseMap = dataProperty.get();
        if (phaseMap == null) {
            hoveringPlot = false;
            return;
        }
        double plotWidth = canvas.getWidth() - PAD_LEFT - PAD_RIGHT;
        double plotHeight = canvas.getHeight() - PAD_TOP - PAD_BOTTOM;
        boolean nextHover = mouseX >= PAD_LEFT && mouseX <= PAD_LEFT + plotWidth
            && mouseY >= PAD_TOP && mouseY <= PAD_TOP + plotHeight;
        if (!nextHover) {
            if (hoveringPlot) {
                hoveringPlot = false;
                scheduleRedraw();
            }
            return;
        }
        double tMin = paneContext.session().viewport.tS.get();
        double tMax = Math.max(paneContext.session().viewport.tE.get(), tMin + 1);
        double yMin = phaseMap.yArr()[0];
        double yMax = phaseMap.yArr()[phaseMap.nY() - 1];
        hoveringPlot = true;
        hoveredTimeMicros = tMin + (mouseX - PAD_LEFT) / Math.max(1, plotWidth) * (tMax - tMin);
        hoveredYValue = yMax - ((mouseY - PAD_TOP) / Math.max(1, plotHeight)) * (yMax - yMin);
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

    private static String formatTime(double micros) {
        return micros >= 1000
            ? String.format("%.2f ms", micros / 1000.0)
            : String.format("%.0f \u03bcs", micros);
    }

    private AxisScrubBar.Bounds overviewBounds() {
        return overviewBounds(canvas.getWidth());
    }

    private static AxisScrubBar.Bounds overviewBounds(double width) {
        return new AxisScrubBar.Bounds(PAD_LEFT, 2, Math.max(1, width - PAD_LEFT - PAD_RIGHT), OVERVIEW_H);
    }

    private static Color cellColour(
        MagnetisationColouringViewModel colouring,
        double phaseDeg,
        double excitation,
        double signalProjection,
        boolean signalProjectionAvailable
    ) {
        if (colouring.isOff()) return null;
        double brightness = MathUtil.clamp(
            MagnetisationColouringSupport.brightnessValue(
                colouring.brightnessSource.get(),
                excitation,
                signalProjection,
                signalProjectionAvailable
            ),
            0,
            1
        );
        return switch (colouring.hueSource.get()) {
            case PHASE -> ColourUtil.hue2color(phaseDeg, brightness);
            case NONE -> colouring.brightnessSource.get() == MagnetisationColouringViewModel.BrightnessSource.NONE
                ? null
                : ColourUtil.monochrome(brightness);
        };
    }

    private static double[] referencePhaseOffsets(PhaseMapData phaseMap, Trajectory referenceTrajectory) {
        if (phaseMap == null || referenceTrajectory == null || phaseMap.data().length == 0) return null;
        PhaseMapData.Cell[] row = null;
        for (var candidate : phaseMap.data()) {
            if (candidate.length > 0) {
                row = candidate;
                break;
            }
        }
        if (row == null) return null;
        var offsets = new double[row.length];
        for (int index = 0; index < row.length; index++) {
            var referenceState = referenceTrajectory.interpolateAt(row[index].tMicros());
            offsets[index] = referenceState != null ? referenceState.phaseDeg() : 0.0;
        }
        return offsets;
    }

    private static boolean[] signalProjectionAvailability(
        PhaseMapData phaseMap,
        BlochData data,
        java.util.List<PulseSegment> pulse
    ) {
        if (phaseMap == null || data == null || data.field() == null || pulse == null) return new boolean[0];
        PhaseMapData.Cell[] row = null;
        for (var candidate : phaseMap.data()) {
            if (candidate.length > 0) {
                row = candidate;
                break;
            }
        }
        if (row == null) return new boolean[0];
        var available = new boolean[row.length];
        for (int index = 0; index < row.length; index++) {
            available[index] = MagnetisationColouringSupport.isSignalProjectionAvailable(
                data.field(),
                pulse,
                row[index].tMicros()
            );
        }
        return available;
    }
}
