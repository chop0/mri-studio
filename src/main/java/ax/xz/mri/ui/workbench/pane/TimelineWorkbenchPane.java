package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.ui.viewmodel.PulseTimelineAnalysis;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.CanvasWorkbenchPane;
import ax.xz.mri.util.MathUtil;
import ax.xz.mri.util.SiFormat;
import javafx.scene.Cursor;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

import static ax.xz.mri.ui.theme.StudioTheme.AC;
import static ax.xz.mri.ui.theme.StudioTheme.BG;
import static ax.xz.mri.ui.theme.StudioTheme.BG2;
import static ax.xz.mri.ui.theme.StudioTheme.CUR;
import static ax.xz.mri.ui.theme.StudioTheme.GR;
import static ax.xz.mri.ui.theme.StudioTheme.TX2;
import static ax.xz.mri.ui.theme.StudioTheme.UI_7;
import static ax.xz.mri.ui.theme.StudioTheme.UI_BOLD_7;

/** Timeline pane using the invariant-preserving viewport view model. */
public class TimelineWorkbenchPane extends CanvasWorkbenchPane {
    private static final int DRAG_ANALYSIS_START = 1;
    private static final int DRAG_ANALYSIS_END = 2;
    private static final int DRAG_CURSOR = 3;
    private static final int DRAG_PAN_VIEW = 4;
    private static final int DRAG_ANALYSIS_WINDOW = 5;

    private static final double PAD_L = 40;
    private static final double PAD_R = 6;
    private static final double PAD_T = 2;
    private static final double PAD_B = 12;
    private static final double OVERVIEW_H = 10;
    private static final double OVERVIEW_GAP = 7;

    private record MeasurementHoverTarget(
        PulseTimelineAnalysis.MeasurementWindow measurement,
        double centerX,
        double dotY
    ) {
    }

    private record TransmitHoverTarget(
        int ordinal,
        PulseTimelineAnalysis.TimeWindow window
    ) {
    }

    private int dragMode;
    private double dragStartX;
    private double dragStartVS;
    private double dragStartVE;
    private double dragStartTS;
    private double dragStartTE;
    private int hoveredTrackIndex = -1;
    private double hoveredMouseX;
    private double hoveredMouseY;
    private MeasurementHoverTarget hoveredMeasurement;
    private TransmitHoverTarget hoveredTransmitWindow;
    private final AxisScrubBar.Interaction overviewInteraction;

    public TimelineWorkbenchPane(PaneContext paneContext) {
        super(paneContext);
        setPaneTitle("Timeline");
        setToolNodes();
        var viewport = paneContext.session().viewport;
        var timelineCtl = paneContext.session().timeline.viewportController;
        overviewInteraction = new AxisScrubBar.Interaction(
            AxisScrubBar.Orientation.HORIZONTAL,
            AxisScrubBar.WindowModel.of(
                () -> 0,
                () -> Math.max(viewport.maxTime.get(), 1),
                viewport.vS::get, viewport.vE::get,
                timelineCtl::setViewport,
                timelineCtl::zoomViewportAround,
                timelineCtl::fitViewportToData,
                null
            )
        );

        bindRedraw(
            paneContext.session().document.currentPulse,
            paneContext.session().document.simulationOutput,
            paneContext.session().derived.signalTrace,
            paneContext.session().viewport.tS,
            paneContext.session().viewport.tE,
            paneContext.session().viewport.vS,
            paneContext.session().viewport.vE,
            paneContext.session().viewport.tC
        );

        canvas.setOnMousePressed(event -> {
            double x = event.getX();
            dragStartVS = paneContext.session().viewport.vS.get();
            dragStartVE = paneContext.session().viewport.vE.get();
            dragStartTS = paneContext.session().viewport.tS.get();
            dragStartTE = paneContext.session().viewport.tE.get();

            if (overviewInteraction.handlePress(overviewBounds(), event)) {
                updateCursor(event.getX(), event.getY());
                updateStatus(event.getX(), event.getY());
                return;
            }

            double startHandle = timeToPixel(paneContext.session().viewport.tS.get());
            double endHandle = timeToPixel(paneContext.session().viewport.tE.get());
            double cursorHandle = timeToPixel(paneContext.session().viewport.tC.get());
            if (Math.abs(x - cursorHandle) < 6) {
                dragMode = DRAG_CURSOR;
            } else if (Math.abs(x - startHandle) < 6) {
                dragMode = DRAG_ANALYSIS_START;
            } else if (Math.abs(x - endHandle) < 6) {
                dragMode = DRAG_ANALYSIS_END;
            } else if (x > startHandle + 6 && x < endHandle - 6) {
                dragMode = DRAG_ANALYSIS_WINDOW;
            } else {
                dragMode = DRAG_PAN_VIEW;
            }
            dragStartX = x;
        });
        canvas.setOnMouseDragged(event -> {
            if (overviewInteraction.handleDrag(overviewBounds(), event)) {
                updateHover(event.getX(), event.getY());
                updateCursor(event.getX(), event.getY());
                updateStatus(event.getX(), event.getY());
                scheduleRedraw();
                return;
            }
            double time = pixelToTime(event.getX());
            double plotSpan = Math.max(1, dragStartVE - dragStartVS);
            double plotWidth = Math.max(1, canvas.getWidth() - PAD_L - PAD_R);
            double plotDelta = (dragStartX - event.getX()) / plotWidth * plotSpan;
            switch (dragMode) {
                case DRAG_ANALYSIS_START -> paneContext.session().timeline.viewportController.moveAnalysisStart(time);
                case DRAG_ANALYSIS_END -> paneContext.session().timeline.viewportController.moveAnalysisEnd(time);
                case DRAG_CURSOR -> paneContext.session().timeline.viewportController.setCursor(time);
                case DRAG_PAN_VIEW -> paneContext.session().timeline.viewportController.setViewport(
                    dragStartVS + plotDelta,
                    dragStartVE + plotDelta
                );
                case DRAG_ANALYSIS_WINDOW -> paneContext.session().timeline.viewportController.setAnalysisWindow(
                    dragStartTS - plotDelta,
                    dragStartTE - plotDelta
                );
                default -> {
                }
            }
            updateHover(event.getX(), event.getY());
            updateCursor(event.getX(), event.getY());
            updateStatus(event.getX(), event.getY());
        });
        canvas.setOnMouseReleased(event -> {
            dragMode = 0;
            overviewInteraction.handleRelease();
            canvas.setCursor(Cursor.DEFAULT);
        });
        canvas.setOnMouseMoved(event -> {
            updateHover(event.getX(), event.getY());
            updateCursor(event.getX(), event.getY());
            updateStatus(event.getX(), event.getY());
        });
        canvas.setOnScroll(event -> {
            if (overviewInteraction.handleScroll(overviewBounds(), event)) {
                scheduleRedraw();
                updateStatus(event.getX(), event.getY());
                return;
            }
            // ⌘/Ctrl + wheel zooms toward the cursor; bare wheel pans
            // horizontally. Two-finger trackpad swipes (deltaX) also pan.
            var ctl = paneContext.session().timeline.viewportController;
            if (event.isShortcutDown()) {
                ctl.zoomViewportAround(pixelToTime(event.getX()),
                    event.getDeltaY() > 0 ? 0.8 : 1.25);
                return;
            }
            double pxDelta = event.getDeltaX() != 0 ? event.getDeltaX() : event.getDeltaY();
            if (pxDelta == 0) return;
            double vp = paneContext.session().viewport.vE.get() - paneContext.session().viewport.vS.get();
            double plotWidth = Math.max(1, canvas.getWidth() - PAD_L - PAD_R);
            ctl.panViewportBy(-pxDelta * vp / plotWidth);
        });
        canvas.setOnZoom(event -> {
            // Trackpad pinch — continuous factor from JavaFX.
            paneContext.session().timeline.viewportController.zoomViewportAround(
                pixelToTime(event.getX()), 1.0 / event.getZoomFactor());
        });
        canvas.setOnContextMenuRequested(event -> {
            var menu = buildContextMenu(event.getX(), event.getY());
            showCanvasContextMenu(menu, event.getScreenX(), event.getScreenY());
        });
    }

    @Override
    protected void paint(javafx.scene.canvas.GraphicsContext g, double width, double height) {
        var pulse = paneContext.session().document.currentPulse.get();
        var data = paneContext.session().document.simulationOutput.get();
        g.setFill(BG);
        g.fillRect(0, 0, width, height);
        if (pulse == null || data == null || data.field() == null) return;

        var field = data.field();
        var signalTrace = paneContext.session().derived.signalTrace.get();
        var analysis = PulseTimelineAnalysis.compute(data, pulse, signalTrace);
        double domainEnd = Math.max(paneContext.session().viewport.maxTime.get(), 1);
        var overview = overviewBounds(width);
        AxisScrubBar.draw(
            g,
            overview,
            AxisScrubBar.Spec.horizontal(
                0,
                domainEnd,
                paneContext.session().viewport.vS.get(),
                paneContext.session().viewport.vE.get(),
                analysis.rfWindows().stream().map(window -> new AxisScrubBar.Span(
                    window.startMicros(),
                    window.endMicros(),
                    Color.web("#1565c0"),
                    0.20
                )).toList(),
                java.util.List.of(new AxisScrubBar.Marker(paneContext.session().viewport.tC.get(), CUR, 0.85, 1.0))
            )
        );

        double mainTop = PAD_T + OVERVIEW_H + OVERVIEW_GAP;
        double plotHeight = height - mainTop - PAD_B;
        double plotWidth = width - PAD_L - PAD_R;
        double viewStart = paneContext.session().viewport.vS.get();
        double viewEnd = paneContext.session().viewport.vE.get();
        double viewSpan = viewEnd - viewStart;

        double signalMax = 1e-6;
        if (signalTrace != null) {
            for (var point : signalTrace.points()) {
                signalMax = Math.max(signalMax, point.signal());
            }
        }

        var tracks = paneContext.session().timeline.tracks;
        double[] maxValues = {250e-6, 0.035, 0.035, signalMax};
        double trackHeight = plotHeight / 4.0;

        for (int trackIndex = 0; trackIndex < 4; trackIndex++) {
            var track = tracks.get(trackIndex);
            double y0 = mainTop + trackIndex * trackHeight;
            g.setFill(trackIndex % 2 == 1 ? BG2 : BG);
            g.fillRect(PAD_L, y0, plotWidth, trackHeight);
            if (trackIndex == hoveredTrackIndex) {
                g.setFill(Color.color(0.0, 0.47, 0.83, 0.06));
                g.fillRect(PAD_L, y0, plotWidth, trackHeight);
            }
            g.setStroke(GR);
            g.setLineWidth(0.5);
            g.strokeLine(PAD_L, y0 + trackHeight, PAD_L + plotWidth, y0 + trackHeight);

            g.setFill(TX2);
            g.setFont(UI_BOLD_7);
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(track.label(), PAD_L - 4, y0 + trackHeight / 2 + 3);
            g.setTextAlign(TextAlignment.LEFT);

            if (track.centered()) {
                g.setStroke(Color.color(0, 0, 0, 0.06));
                g.setLineWidth(0.5);
                g.strokeLine(PAD_L, y0 + trackHeight / 2, PAD_L + plotWidth, y0 + trackHeight / 2);
            }

            for (var rfWindow : analysis.rfWindows()) {
                if (rfWindow.endMicros() < viewStart || rfWindow.startMicros() > viewEnd) continue;
                double xStart = Math.max(PAD_L, timeToPixel(rfWindow.startMicros()));
                double xEnd = Math.min(PAD_L + plotWidth, timeToPixel(rfWindow.endMicros()));
                g.setFill(Color.color(0, 0, 0, 0.03));
                g.fillRect(xStart, y0, xEnd - xStart, trackHeight);
            }

            for (int segmentIndex = 1; segmentIndex < analysis.segmentWindows().size(); segmentIndex++) {
                double dividerTime = analysis.segmentWindows().get(segmentIndex).startMicros();
                if (dividerTime < viewStart || dividerTime > viewEnd) continue;
                double x = timeToPixel(dividerTime);
                g.setStroke(Color.color(0, 0, 0, 0.08));
                g.setLineWidth(0.5);
                g.strokeLine(x, y0, x, y0 + trackHeight);
            }

            g.save();
            g.beginPath();
            g.rect(PAD_L, y0, plotWidth, trackHeight);
            g.clip();
            g.beginPath();
            g.setStroke(track.colour());
            g.setLineWidth(1.2);
            g.setGlobalAlpha(0.85);
            boolean started = false;
            if (trackIndex == 3) {
                if (signalTrace != null) {
                    for (var point : signalTrace.points()) {
                        if (point.tMicros() < viewStart - viewSpan * 0.01 || point.tMicros() > viewEnd + viewSpan * 0.01) continue;
                        double x = timeToPixel(point.tMicros());
                        double y = y0 + trackHeight - (point.signal() / maxValues[trackIndex]) * trackHeight * 0.85;
                        if (!started) {
                            g.moveTo(x, y);
                            started = true;
                        } else {
                            g.lineTo(x, y);
                        }
                    }
                }
            } else {
                for (int segmentIndex = 0; segmentIndex < field.segments.size() && segmentIndex < pulse.size(); segmentIndex++) {
                    var segment = field.segments.get(segmentIndex);
                    var steps = pulse.get(segmentIndex).steps();
                    double t = analysis.segmentWindows().get(segmentIndex).startMicros();
                    for (var step : steps) {
                        if (t >= viewStart - viewSpan * 0.01 && t <= viewEnd + viewSpan * 0.01) {
                            // Legacy channel layout preserved for the timeline viewer:
                            //   0,1 = b1x, b1y (I, Q) → displayed as magnitude when RF gate is on
                            //   2   = gx
                            //   3   = gz
                            double value = switch (trackIndex) {
                                case 0 -> step.isRfOn()
                                    ? Math.hypot(step.control(0), step.control(1)) : 0.0;
                                case 1 -> step.control(3);  // Gz
                                default -> step.control(2); // Gx
                            };
                            double x = timeToPixel(t);
                            double y = track.centered()
                                ? y0 + trackHeight / 2 - (value / maxValues[trackIndex]) * trackHeight / 2
                                : y0 + trackHeight - (value / maxValues[trackIndex]) * trackHeight * 0.85;
                            if (!started) {
                                g.moveTo(x, y);
                                started = true;
                            } else {
                                g.lineTo(x, y);
                            }
                        }
                        t += segment.dt() * 1e6;
                    }
                }
            }
            g.stroke();
            g.setGlobalAlpha(1);
            g.restore();

            if (trackIndex == 3) {
                drawMeasurementAverages(g, analysis, signalTrace, y0, trackHeight, signalMax, viewStart, viewEnd);
            }
        }

        double analysisStartX = MathUtil.clamp(timeToPixel(paneContext.session().viewport.tS.get()), PAD_L, PAD_L + plotWidth);
        double analysisEndX = MathUtil.clamp(timeToPixel(paneContext.session().viewport.tE.get()), PAD_L, PAD_L + plotWidth);
        g.setFill(AC);
        g.setGlobalAlpha(0.08);
        g.fillRect(analysisStartX, mainTop, analysisEndX - analysisStartX, plotHeight);
        g.setGlobalAlpha(1);
        for (double x : new double[]{analysisStartX, analysisEndX}) {
            g.setFill(AC);
            g.setGlobalAlpha(0.7);
            g.fillRect(x - 1.5, mainTop, 3, plotHeight);
            g.setGlobalAlpha(1);
        }

        double cursorX = MathUtil.clamp(timeToPixel(paneContext.session().viewport.tC.get()), PAD_L, PAD_L + plotWidth);
        g.setStroke(CUR);
        g.setLineWidth(1.5);
        g.setGlobalAlpha(0.8);
        g.strokeLine(cursorX, mainTop, cursorX, mainTop + plotHeight);
        g.setGlobalAlpha(1);
        drawBadge(g, cursorX, mainTop + 10, SiFormat.time(paneContext.session().viewport.tC.get()), CUR);

        g.setFont(UI_BOLD_7);
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(TX2);
        g.setGlobalAlpha(0.3);
        for (int segmentIndex = 0; segmentIndex < analysis.segmentWindows().size(); segmentIndex++) {
            var bounds = analysis.segmentWindows().get(segmentIndex);
            if (bounds.endMicros() < viewStart || bounds.startMicros() > viewEnd) continue;
            g.fillText(
                String.valueOf(segmentIndex),
                (timeToPixel(bounds.startMicros()) + timeToPixel(bounds.endMicros())) / 2,
                mainTop + 8
            );
        }
        g.setTextAlign(TextAlignment.LEFT);
        g.setGlobalAlpha(1);

        int tickStep = niceTick(viewSpan);
        g.setFill(TX2);
        g.setFont(UI_7);
        g.setTextAlign(TextAlignment.CENTER);
        g.setGlobalAlpha(0.6);
        for (double tick = Math.ceil(viewStart / tickStep) * tickStep; tick <= viewEnd; tick += tickStep) {
            double x = timeToPixel(tick);
            if (x > PAD_L + 4 && x < PAD_L + plotWidth - 4) {
                g.fillText(String.format("%.0fms", tick / 1000), x, height - 2);
            }
        }
        g.setTextAlign(TextAlignment.LEFT);
        g.setGlobalAlpha(1);
        drawHoverOverlay(g, analysis, signalTrace, signalMax, mainTop, trackHeight, plotHeight, viewStart, viewEnd);
    }

    private ContextMenu buildContextMenu(double mouseX, double mouseY) {
        var menu = new ContextMenu();
        if (overviewBounds().contains(mouseX, mouseY)) {
            var overviewLabel = new MenuItem("Overview");
            overviewLabel.setDisable(true);
            menu.getItems().addAll(overviewLabel, new SeparatorMenuItem(), overviewInteraction.newResetMenuItem());
            return menu;
        }
        if (hoveredMeasurement != null) {
            var title = new MenuItem("Average Receive Window");
            title.setDisable(true);
            var setCursor = new MenuItem("Set Cursor To Window Centre");
            setCursor.setOnAction(event -> paneContext.session().timeline.viewportController.setCursor(hoveredMeasurement.measurement().centerMicros()));
            var zoom = new MenuItem("Zoom To Window");
            zoom.setOnAction(event -> {
                double span = Math.max(10.0, hoveredMeasurement.measurement().endMicros() - hoveredMeasurement.measurement().startMicros());
                double margin = span * 0.35;
                paneContext.session().timeline.viewportController.setViewport(
                    hoveredMeasurement.measurement().startMicros() - margin,
                    hoveredMeasurement.measurement().endMicros() + margin
                );
            });
            var useAnalysis = new MenuItem("Use As Analysis Window");
            useAnalysis.setOnAction(event -> paneContext.session().timeline.viewportController.setAnalysisWindow(
                hoveredMeasurement.measurement().startMicros(),
                hoveredMeasurement.measurement().endMicros()
            ));
            menu.getItems().addAll(title, new SeparatorMenuItem(), setCursor, zoom, useAnalysis, new SeparatorMenuItem(), overviewInteraction.newResetMenuItem());
            return menu;
        }
        if (hoveredTransmitWindow != null) {
            var title = new MenuItem(transmitWindowLabel(hoveredTransmitWindow));
            title.setDisable(true);
            var setCursor = new MenuItem("Set Cursor To Window Centre");
            setCursor.setOnAction(event -> paneContext.session().timeline.viewportController.setCursor(hoveredTransmitWindow.window().centerMicros()));
            var zoom = new MenuItem("Zoom To Window");
            zoom.setOnAction(event -> {
                double span = Math.max(10.0, hoveredTransmitWindow.window().endMicros() - hoveredTransmitWindow.window().startMicros());
                double margin = span * 0.35;
                paneContext.session().timeline.viewportController.setViewport(
                    hoveredTransmitWindow.window().startMicros() - margin,
                    hoveredTransmitWindow.window().endMicros() + margin
                );
            });
            menu.getItems().addAll(title, new SeparatorMenuItem(), setCursor, zoom, new SeparatorMenuItem(), overviewInteraction.newResetMenuItem());
            return menu;
        }
        var setCursor = new MenuItem("Set cursor here");
        setCursor.setOnAction(event -> paneContext.session().timeline.viewportController.setCursor(pixelToTime(mouseX)));
        var fit = new MenuItem("Fit to data");
        fit.setOnAction(event -> paneContext.session().timeline.viewportController.fitViewportToData());
        var resetAnalysis = new MenuItem("Analysis window = viewport");
        resetAnalysis.setOnAction(event -> paneContext.session().timeline.viewportController.setAnalysisWindowToViewport());
        var zoomToAnalysis = new MenuItem("Viewport = analysis window");
        zoomToAnalysis.setOnAction(event -> paneContext.session().timeline.viewportController.zoomViewportToAnalysisWindow());
        var trackLabel = new MenuItem("Track: " + hoveredTrackLabel(mouseY));
        trackLabel.setDisable(true);
        menu.getItems().addAll(trackLabel, new SeparatorMenuItem(), setCursor, fit, resetAnalysis, zoomToAnalysis, new SeparatorMenuItem(), overviewInteraction.newResetMenuItem());
        return menu;
    }

    private void updateCursor(double mouseX, double mouseY) {
        var overview = overviewBounds();
        if (overview.contains(mouseX, mouseY)) {
            canvas.setCursor(overviewInteraction.cursor(overview, mouseX, mouseY));
            return;
        }
        double start = timeToPixel(paneContext.session().viewport.tS.get());
        double end = timeToPixel(paneContext.session().viewport.tE.get());
        double cursor = timeToPixel(paneContext.session().viewport.tC.get());
        if (Math.abs(mouseX - start) < 6 || Math.abs(mouseX - end) < 6 || Math.abs(mouseX - cursor) < 6) {
            canvas.setCursor(Cursor.H_RESIZE);
        } else if (mouseX > start + 6 && mouseX < end - 6) {
            canvas.setCursor(Cursor.CLOSED_HAND);
        } else {
            canvas.setCursor(Cursor.OPEN_HAND);
        }
    }

    private void updateStatus(double mouseX, double mouseY) {
        if (hoveredMeasurement != null) {
            var measurement = hoveredMeasurement.measurement();
            setPaneStatus(String.format(
                "Average receive signal | <S>=%.3f | norm=%.3f | t=[%.1f, %.1f] \u03bcs | \u0394t=%.1f \u03bcs",
                measurement.averageSignal(),
                measurement.normalizedAverage(),
                measurement.startMicros(),
                measurement.endMicros(),
                measurement.endMicros() - measurement.startMicros()
            ));
            return;
        }
        if (hoveredTransmitWindow != null) {
            setPaneStatus(String.format(
                "%s | t=[%.1f, %.1f] \u03bcs | \u0394t=%.1f \u03bcs",
                transmitWindowLabel(hoveredTransmitWindow),
                hoveredTransmitWindow.window().startMicros(),
                hoveredTransmitWindow.window().endMicros(),
                hoveredTransmitWindow.window().durationMicros()
            ));
            return;
        }
        setPaneStatus(String.format(
            "%s | t=%.1f \u03bcs | analysis=[%.1f, %.1f] \u03bcs",
            hoveredTrackLabel(mouseY),
            pixelToTime(mouseX),
            paneContext.session().viewport.tS.get(),
            paneContext.session().viewport.tE.get()
        ));
    }

    private String hoveredTrackLabel(double mouseY) {
        double plotHeight = canvas.getHeight() - (PAD_T + OVERVIEW_H + OVERVIEW_GAP) - PAD_B;
        double trackHeight = plotHeight / 4.0;
        int index = (int) Math.max(0, Math.min(3, (mouseY - (PAD_T + OVERVIEW_H + OVERVIEW_GAP)) / trackHeight));
        return paneContext.session().timeline.tracks.get(index).label();
    }

    private double timeToPixel(double time) {
        double viewStart = paneContext.session().viewport.vS.get();
        double viewEnd = paneContext.session().viewport.vE.get();
        double plotWidth = canvas.getWidth() - PAD_L - PAD_R;
        return PAD_L + (time - viewStart) / (viewEnd - viewStart) * plotWidth;
    }

    private double pixelToTime(double pixel) {
        double viewStart = paneContext.session().viewport.vS.get();
        double viewEnd = paneContext.session().viewport.vE.get();
        double plotWidth = canvas.getWidth() - PAD_L - PAD_R;
        return viewStart + (pixel - PAD_L) / plotWidth * (viewEnd - viewStart);
    }

    private static int niceTick(double span) {
        return span > 5000 ? 2000 : span > 2000 ? 1000 : span > 800 ? 500 : 200;
    }

    private void updateHover(double mouseX, double mouseY) {
        double mainTop = PAD_T + OVERVIEW_H + OVERVIEW_GAP;
        double plotHeight = canvas.getHeight() - mainTop - PAD_B;
        double plotWidth = canvas.getWidth() - PAD_L - PAD_R;
        int nextHovered = -1;
        hoveredMouseX = mouseX;
        hoveredMouseY = mouseY;
        if (mouseX >= PAD_L && mouseX <= PAD_L + plotWidth && mouseY >= mainTop && mouseY <= mainTop + plotHeight) {
            nextHovered = Math.max(0, Math.min(3, (int) ((mouseY - mainTop) / Math.max(1, plotHeight / 4.0))));
        }
        hoveredTrackIndex = nextHovered;
        hoveredMeasurement = findHoveredMeasurement(mouseX, mouseY, mainTop, plotHeight / 4.0);
        hoveredTransmitWindow = findHoveredTransmitWindow(mouseX, mouseY, mainTop, plotHeight);
        scheduleRedraw();
    }

    private void drawBadge(javafx.scene.canvas.GraphicsContext g, double centerX, double y, String text, Color accent) {
        ax.xz.mri.ui.canvas.CanvasDrawingUtils.drawBadge(g, centerX, y, text, accent, UI_BOLD_7,
            36, PAD_L + 4, canvas.getWidth() - PAD_R - 4);
    }

    private void drawMeasurementAverages(
        javafx.scene.canvas.GraphicsContext g,
        PulseTimelineAnalysis.Analysis analysis,
        ax.xz.mri.model.simulation.SignalTrace signalTrace,
        double trackTop,
        double trackHeight,
        double signalMax,
        double viewStart,
        double viewEnd
    ) {
        if (analysis.measurements().isEmpty()) return;

        g.setFont(UI_BOLD_7);
        for (var measurement : analysis.measurements()) {
            if (measurement.endMicros() < viewStart || measurement.startMicros() > viewEnd) continue;

            double centerX = MathUtil.clamp(timeToPixel(measurement.centerMicros()), PAD_L, canvas.getWidth() - PAD_R);
            double normalizedValue = signalMax <= 1e-9 ? 0.0 : measurement.averageSignal() / signalMax;
            double dotY = trackTop + trackHeight - MathUtil.clamp(normalizedValue, 0, 1) * trackHeight * 0.85;
            double labelY = trackTop + 4 + ((measurement.ordinal() - 1) % 2) * 12;
            String label = String.format("<S>=%.2f", measurement.normalizedAverage());
            double x0 = Math.max(PAD_L, timeToPixel(measurement.startMicros()));
            double x1 = Math.min(PAD_L + (canvas.getWidth() - PAD_L - PAD_R), timeToPixel(measurement.endMicros()));

            g.setFill(Color.color(0.18, 0.60, 0.28, measurement.equals(hoveredMeasurement == null ? null : hoveredMeasurement.measurement()) ? 0.14 : 0.06));
            g.fillRect(x0, trackTop, Math.max(0, x1 - x0), trackHeight);
            drawMeasurementArea(g, signalTrace, measurement, trackTop, trackHeight, signalMax, viewStart, viewEnd, measurement.equals(hoveredMeasurement == null ? null : hoveredMeasurement.measurement()));

            g.setStroke(Color.color(0.18, 0.6, 0.28, 0.28));
            g.setLineWidth(0.7);
            g.strokeLine(centerX, labelY + 11, centerX, dotY - 5);

            g.setFill(Color.color(0.18, 0.6, 0.28, 0.95));
            g.fillOval(centerX - 3, dotY - 3, 6, 6);
            g.setStroke(Color.color(0.11, 0.15, 0.12, 0.55));
            g.setLineWidth(0.8);
            g.strokeOval(centerX - 3, dotY - 3, 6, 6);
            if (measurement.equals(hoveredMeasurement == null ? null : hoveredMeasurement.measurement())) {
                g.setStroke(Color.color(0.18, 0.6, 0.28, 0.95));
                g.setLineWidth(1.3);
                g.strokeOval(centerX - 5, dotY - 5, 10, 10);
            }

            drawBadge(g, centerX, labelY, label, Color.web("#2e7d32"));
        }
    }

    private MeasurementHoverTarget findHoveredMeasurement(double mouseX, double mouseY, double mainTop, double trackHeight) {
        if (hoveredTrackIndex != 3) return null;
        var analysis = PulseTimelineAnalysis.compute(
            paneContext.session().document.simulationOutput.get(),
            paneContext.session().document.currentPulse.get(),
            paneContext.session().derived.signalTrace.get()
        );
        double signalTrackTop = mainTop + trackHeight * 3.0;
        double signalMax = Math.max(1e-6, maxSignalValue(paneContext.session().derived.signalTrace.get()));
        for (var measurement : analysis.measurements()) {
            double x0 = Math.max(PAD_L, timeToPixel(measurement.startMicros()));
            double x1 = Math.min(PAD_L + (canvas.getWidth() - PAD_L - PAD_R), timeToPixel(measurement.endMicros()));
            double centerX = MathUtil.clamp(timeToPixel(measurement.centerMicros()), PAD_L, canvas.getWidth() - PAD_R);
            double normalizedValue = signalMax <= 1e-9 ? 0.0 : measurement.averageSignal() / signalMax;
            double dotY = signalTrackTop + trackHeight - MathUtil.clamp(normalizedValue, 0, 1) * trackHeight * 0.85;
            boolean nearDot = Math.hypot(mouseX - centerX, mouseY - dotY) <= 9.0;
            boolean insideWindow = mouseX >= x0 && mouseX <= x1 && mouseY >= signalTrackTop && mouseY <= signalTrackTop + trackHeight;
            if (nearDot || insideWindow) {
                return new MeasurementHoverTarget(measurement, centerX, dotY);
            }
        }
        return null;
    }

    private TransmitHoverTarget findHoveredTransmitWindow(double mouseX, double mouseY, double mainTop, double plotHeight) {
        if (mouseX < PAD_L || mouseX > canvas.getWidth() - PAD_R || mouseY < mainTop || mouseY > mainTop + plotHeight) {
            return null;
        }
        double time = pixelToTime(mouseX);
        var analysis = PulseTimelineAnalysis.compute(
            paneContext.session().document.simulationOutput.get(),
            paneContext.session().document.currentPulse.get(),
            paneContext.session().derived.signalTrace.get()
        );
        for (int index = 0; index < analysis.rfWindows().size(); index++) {
            var window = analysis.rfWindows().get(index);
            if (time >= window.startMicros() && time <= window.endMicros()) {
                return new TransmitHoverTarget(index + 1, window);
            }
        }
        return null;
    }

    private void drawMeasurementArea(
        javafx.scene.canvas.GraphicsContext g,
        ax.xz.mri.model.simulation.SignalTrace signalTrace,
        PulseTimelineAnalysis.MeasurementWindow measurement,
        double trackTop,
        double trackHeight,
        double signalMax,
        double viewStart,
        double viewEnd,
        boolean hovered
    ) {
        if (signalTrace == null || signalTrace.points().isEmpty()) return;
        double start = Math.max(viewStart, measurement.startMicros());
        double end = Math.min(viewEnd, measurement.endMicros());
        if (end <= start) return;
        double baselineY = trackTop + trackHeight;
        g.save();
        g.beginPath();
        g.rect(PAD_L, trackTop, canvas.getWidth() - PAD_L - PAD_R, trackHeight);
        g.clip();
        g.beginPath();
        g.moveTo(timeToPixel(start), baselineY);
        appendSignalAreaPoint(g, start, signalAt(signalTrace, start), trackTop, trackHeight, signalMax);
        for (var point : signalTrace.points()) {
            if (point.tMicros() <= start || point.tMicros() >= end) continue;
            appendSignalAreaPoint(g, point.tMicros(), point.signal(), trackTop, trackHeight, signalMax);
        }
        appendSignalAreaPoint(g, end, signalAt(signalTrace, end), trackTop, trackHeight, signalMax);
        g.lineTo(timeToPixel(end), baselineY);
        g.closePath();
        g.setFill(Color.color(0.18, 0.60, 0.28, hovered ? 0.24 : 0.14));
        g.fill();
        g.restore();
    }

    private void appendSignalAreaPoint(
        javafx.scene.canvas.GraphicsContext g,
        double timeMicros,
        double signal,
        double trackTop,
        double trackHeight,
        double signalMax
    ) {
        double x = timeToPixel(timeMicros);
        double y = trackTop + trackHeight - MathUtil.clamp(signalMax <= 1e-9 ? 0.0 : signal / signalMax, 0, 1) * trackHeight * 0.85;
        g.lineTo(x, y);
    }

    private double signalAt(ax.xz.mri.model.simulation.SignalTrace signalTrace, double timeMicros) {
        if (signalTrace == null || signalTrace.points().isEmpty()) return 0.0;
        var points = signalTrace.points();
        if (timeMicros <= points.get(0).tMicros()) return points.get(0).signal();
        if (timeMicros >= points.get(points.size() - 1).tMicros()) return points.get(points.size() - 1).signal();
        for (int index = 1; index < points.size(); index++) {
            var right = points.get(index);
            if (timeMicros > right.tMicros()) continue;
            var left = points.get(index - 1);
            double span = right.tMicros() - left.tMicros();
            double fraction = span <= 1e-9 ? 0.0 : (timeMicros - left.tMicros()) / span;
            return left.signal() + fraction * (right.signal() - left.signal());
        }
        return points.get(points.size() - 1).signal();
    }

    private double maxSignalValue(ax.xz.mri.model.simulation.SignalTrace signalTrace) {
        if (signalTrace == null) return 1e-6;
        double max = 1e-6;
        for (var point : signalTrace.points()) {
            max = Math.max(max, point.signal());
        }
        return max;
    }

    private void drawHoverOverlay(
        javafx.scene.canvas.GraphicsContext g,
        PulseTimelineAnalysis.Analysis analysis,
        ax.xz.mri.model.simulation.SignalTrace signalTrace,
        double signalMax,
        double mainTop,
        double trackHeight,
        double plotHeight,
        double viewStart,
        double viewEnd
    ) {
        if (hoveredMeasurement != null) {
            drawInfoCard(
                g,
                hoveredMouseX,
                hoveredMouseY,
                Color.web("#2e7d32"),
                java.util.List.of(
                    "Average receive signal",
                    String.format("<S> = %.3f", hoveredMeasurement.measurement().averageSignal()),
                    String.format("norm = %.3f", hoveredMeasurement.measurement().normalizedAverage()),
                    String.format("t = %.1f .. %.1f \u03bcs", hoveredMeasurement.measurement().startMicros(), hoveredMeasurement.measurement().endMicros()),
                    String.format("\u0394t = %.1f \u03bcs", hoveredMeasurement.measurement().endMicros() - hoveredMeasurement.measurement().startMicros())
                )
            );
            return;
        }
        if (hoveredTransmitWindow != null) {
            double x0 = Math.max(PAD_L, timeToPixel(hoveredTransmitWindow.window().startMicros()));
            double x1 = Math.min(PAD_L + (canvas.getWidth() - PAD_L - PAD_R), timeToPixel(hoveredTransmitWindow.window().endMicros()));
            g.setStroke(Color.color(0.08, 0.40, 0.75, 0.65));
            g.setLineWidth(1.0);
            g.strokeRect(x0, mainTop, Math.max(0, x1 - x0), plotHeight);
            drawInfoCard(
                g,
                hoveredMouseX,
                hoveredMouseY,
                Color.web("#1565c0"),
                java.util.List.of(
                    transmitWindowLabel(hoveredTransmitWindow),
                    String.format("t = %.1f .. %.1f \u03bcs", hoveredTransmitWindow.window().startMicros(), hoveredTransmitWindow.window().endMicros()),
                    String.format("\u0394t = %.1f \u03bcs", hoveredTransmitWindow.window().durationMicros()),
                    "TX active, receive suppressed"
                )
            );
        }
    }

    private String transmitWindowLabel(TransmitHoverTarget target) {
        return target.ordinal() == 1
            ? "TX Window 1 (initial excitation)"
            : "TX Window " + target.ordinal();
    }

    private void drawInfoCard(javafx.scene.canvas.GraphicsContext g, double anchorX, double anchorY, Color accent, java.util.List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        g.setFont(UI_7);
        int maxLength = lines.stream().mapToInt(String::length).max().orElse(8);
        double width = Math.max(96, maxLength * 5.2 + 16);
        double height = lines.size() * 11 + 10;
        double x = MathUtil.clamp(anchorX + 10, 6, canvas.getWidth() - width - 6);
        double y = MathUtil.clamp(anchorY - height - 6, 6, canvas.getHeight() - height - 6);
        g.setFill(Color.color(0.97, 0.98, 0.99, 0.95));
        g.fillRoundRect(x, y, width, height, 8, 8);
        g.setStroke(Color.color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0.55));
        g.setLineWidth(0.8);
        g.strokeRoundRect(x, y, width, height, 8, 8);
        g.setFill(Color.color(0.15, 0.17, 0.20, 0.96));
        for (int index = 0; index < lines.size(); index++) {
            g.fillText(lines.get(index), x + 8, y + 14 + index * 11);
        }
    }

    private AxisScrubBar.Bounds overviewBounds() {
        return overviewBounds(canvas.getWidth());
    }

    private static AxisScrubBar.Bounds overviewBounds(double width) {
        return new AxisScrubBar.Bounds(PAD_L, PAD_T, Math.max(1, width - PAD_L - PAD_R), OVERVIEW_H);
    }
}
