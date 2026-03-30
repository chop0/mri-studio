package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.CanvasWorkbenchPane;
import ax.xz.mri.util.MathUtil;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;

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
    private record SegmentBounds(double t0, double tE) {}
    private record RfWindow(double t0, double t1) {}

    private static final int DRAG_ANALYSIS_START = 1;
    private static final int DRAG_ANALYSIS_END = 2;
    private static final int DRAG_CURSOR = 3;
    private static final int DRAG_PAN_VIEW = 4;
    private static final int DRAG_ANALYSIS_WINDOW = 5;
    private static final int DRAG_OVERVIEW_START = 6;
    private static final int DRAG_OVERVIEW_END = 7;
    private static final int DRAG_OVERVIEW_WINDOW = 8;

    private static final double PAD_L = 40;
    private static final double PAD_R = 6;
    private static final double PAD_T = 2;
    private static final double PAD_B = 12;
    private static final double OVERVIEW_H = 10;
    private static final double OVERVIEW_GAP = 7;

    private int dragMode;
    private double dragStartX;
    private double dragStartVS;
    private double dragStartVE;
    private double dragStartTS;
    private double dragStartTE;

    public TimelineWorkbenchPane(PaneContext paneContext) {
        super(paneContext);
        setPaneTitle("Timeline");
        setToolNodes();

        bindRedraw(
            paneContext.session().document.currentPulse,
            paneContext.session().document.blochData,
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

            var overview = overviewBounds();
            if (overview.contains(x, event.getY())) {
                double domainEnd = Math.max(paneContext.session().viewport.maxTime.get(), 1);
                double viewStart = OverviewStrip.pixelAt(overview, dragStartVS, 0, domainEnd);
                double viewEnd = OverviewStrip.pixelAt(overview, dragStartVE, 0, domainEnd);
                if (Math.abs(x - viewStart) <= OverviewStrip.HANDLE_HIT_RADIUS) {
                    dragMode = DRAG_OVERVIEW_START;
                } else if (Math.abs(x - viewEnd) <= OverviewStrip.HANDLE_HIT_RADIUS) {
                    dragMode = DRAG_OVERVIEW_END;
                } else {
                    if (x < viewStart || x > viewEnd) {
                        double span = dragStartVE - dragStartVS;
                        double centre = OverviewStrip.valueAt(overview, x, 0, domainEnd);
                        paneContext.session().timeline.viewportController.setViewport(centre - span / 2, centre + span / 2);
                        dragStartVS = paneContext.session().viewport.vS.get();
                        dragStartVE = paneContext.session().viewport.vE.get();
                    }
                    dragMode = DRAG_OVERVIEW_WINDOW;
                }
                dragStartX = x;
                updateCursor(event.getX(), event.getY());
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
                case DRAG_OVERVIEW_START -> paneContext.session().timeline.viewportController.setViewport(
                    OverviewStrip.valueAt(overviewBounds(), event.getX(), 0, Math.max(paneContext.session().viewport.maxTime.get(), 1)),
                    dragStartVE
                );
                case DRAG_OVERVIEW_END -> paneContext.session().timeline.viewportController.setViewport(
                    dragStartVS,
                    OverviewStrip.valueAt(overviewBounds(), event.getX(), 0, Math.max(paneContext.session().viewport.maxTime.get(), 1))
                );
                case DRAG_OVERVIEW_WINDOW -> {
                    double domainEnd = Math.max(paneContext.session().viewport.maxTime.get(), 1);
                    double delta = OverviewStrip.valueAt(overviewBounds(), event.getX(), 0, domainEnd)
                        - OverviewStrip.valueAt(overviewBounds(), dragStartX, 0, domainEnd);
                    paneContext.session().timeline.viewportController.setViewport(dragStartVS + delta, dragStartVE + delta);
                }
                default -> {
                }
            }
            updateCursor(event.getX(), event.getY());
            updateStatus(event.getX(), event.getY());
        });
        canvas.setOnMouseReleased(event -> {
            dragMode = 0;
            canvas.setCursor(Cursor.DEFAULT);
        });
        canvas.setOnMouseMoved(event -> {
            updateCursor(event.getX(), event.getY());
            updateStatus(event.getX(), event.getY());
        });
        canvas.setOnScroll(event -> paneContext.session().timeline.viewportController.zoomViewportAround(
            pixelToTime(event.getX()), event.getDeltaY() > 0 ? 0.8 : 1.25));
        canvas.setOnContextMenuRequested(event -> {
            var menu = buildContextMenu(event.getX(), event.getY());
            showCanvasContextMenu(menu, event.getScreenX(), event.getScreenY());
        });
    }

    @Override
    protected void paint(javafx.scene.canvas.GraphicsContext g, double width, double height) {
        var pulse = paneContext.session().document.currentPulse.get();
        var data = paneContext.session().document.blochData.get();
        g.setFill(BG);
        g.fillRect(0, 0, width, height);
        if (pulse == null || data == null || data.field() == null) return;

        var field = data.field();
        var segmentBounds = new ArrayList<SegmentBounds>();
        var rfWindows = new ArrayList<RfWindow>();
        double accumulatedTime = 0;
        for (int segmentIndex = 0; segmentIndex < field.segments.size() && segmentIndex < pulse.size(); segmentIndex++) {
            var segment = field.segments.get(segmentIndex);
            var steps = pulse.get(segmentIndex).steps();
            segmentBounds.add(new SegmentBounds(accumulatedTime, accumulatedTime + segment.totalSteps() * segment.dt() * 1e6));
            Double rfStart = null;
            double stepTime = accumulatedTime;
            for (var step : steps) {
                if (step.isRfOn() && rfStart == null) rfStart = stepTime;
                if (!step.isRfOn() && rfStart != null) {
                    rfWindows.add(new RfWindow(rfStart, stepTime));
                    rfStart = null;
                }
                stepTime += segment.dt() * 1e6;
            }
            if (rfStart != null) rfWindows.add(new RfWindow(rfStart, stepTime));
            accumulatedTime += segment.totalSteps() * segment.dt() * 1e6;
        }

        double domainEnd = Math.max(paneContext.session().viewport.maxTime.get(), 1);
        var overview = overviewBounds(width);
        OverviewStrip.drawHorizontal(
            g,
            overview,
            0,
            domainEnd,
            paneContext.session().viewport.vS.get(),
            paneContext.session().viewport.vE.get(),
            rfWindows.stream().map(window -> new OverviewStrip.Span(
                window.t0(),
                window.t1(),
                Color.web("#1565c0"),
                0.20
            )).toList(),
            paneContext.session().viewport.tC.get()
        );

        double mainTop = PAD_T + OVERVIEW_H + OVERVIEW_GAP;
        double plotHeight = height - mainTop - PAD_B;
        double plotWidth = width - PAD_L - PAD_R;
        double viewStart = paneContext.session().viewport.vS.get();
        double viewEnd = paneContext.session().viewport.vE.get();
        double viewSpan = viewEnd - viewStart;

        double signalMax = 1e-6;
        var signalTrace = paneContext.session().derived.signalTrace.get();
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

            for (var rfWindow : rfWindows) {
                if (rfWindow.t1() < viewStart || rfWindow.t0() > viewEnd) continue;
                double xStart = Math.max(PAD_L, timeToPixel(rfWindow.t0()));
                double xEnd = Math.min(PAD_L + plotWidth, timeToPixel(rfWindow.t1()));
                g.setFill(Color.color(0, 0, 0, 0.03));
                g.fillRect(xStart, y0, xEnd - xStart, trackHeight);
            }

            for (int segmentIndex = 1; segmentIndex < segmentBounds.size(); segmentIndex++) {
                double dividerTime = segmentBounds.get(segmentIndex).t0();
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
                    double t = segmentBounds.get(segmentIndex).t0();
                    for (var step : steps) {
                        if (t >= viewStart - viewSpan * 0.01 && t <= viewEnd + viewSpan * 0.01) {
                            double value = switch (trackIndex) {
                                case 0 -> step.b1Magnitude();
                                case 1 -> step.gz();
                                default -> step.gx();
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

        g.setFont(UI_BOLD_7);
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(TX2);
        g.setGlobalAlpha(0.3);
        for (int segmentIndex = 0; segmentIndex < segmentBounds.size(); segmentIndex++) {
            var bounds = segmentBounds.get(segmentIndex);
            if (bounds.tE() < viewStart || bounds.t0() > viewEnd) continue;
            g.fillText(String.valueOf(segmentIndex), (timeToPixel(bounds.t0()) + timeToPixel(bounds.tE())) / 2, mainTop + 8);
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
    }

    private ContextMenu buildContextMenu(double mouseX, double mouseY) {
        var menu = new ContextMenu();
        var setCursor = new MenuItem("Set cursor here");
        setCursor.setOnAction(event -> paneContext.session().timeline.viewportController.setCursor(pixelToTime(mouseX)));
        var fit = new MenuItem("Fit to data");
        fit.setOnAction(event -> paneContext.session().timeline.viewportController.fitViewportToData());
        var resetAnalysis = new MenuItem("Analysis window = viewport");
        resetAnalysis.setOnAction(event -> paneContext.session().timeline.viewportController.setAnalysisWindowToViewport());
        var trackLabel = new MenuItem("Track: " + hoveredTrackLabel(mouseY));
        trackLabel.setDisable(true);
        menu.getItems().addAll(trackLabel, new SeparatorMenuItem(), setCursor, fit, resetAnalysis);
        return menu;
    }

    private void updateCursor(double mouseX, double mouseY) {
        var overview = overviewBounds();
        if (overview.contains(mouseX, mouseY)) {
            double domainEnd = Math.max(paneContext.session().viewport.maxTime.get(), 1);
            double viewStart = OverviewStrip.pixelAt(overview, paneContext.session().viewport.vS.get(), 0, domainEnd);
            double viewEnd = OverviewStrip.pixelAt(overview, paneContext.session().viewport.vE.get(), 0, domainEnd);
            if (Math.abs(mouseX - viewStart) < OverviewStrip.HANDLE_HIT_RADIUS
                || Math.abs(mouseX - viewEnd) < OverviewStrip.HANDLE_HIT_RADIUS) {
                canvas.setCursor(Cursor.H_RESIZE);
            } else if (mouseX > viewStart && mouseX < viewEnd) {
                canvas.setCursor(Cursor.CLOSED_HAND);
            } else {
                canvas.setCursor(Cursor.OPEN_HAND);
            }
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

    private OverviewStrip.Bounds overviewBounds() {
        return overviewBounds(canvas.getWidth());
    }

    private static OverviewStrip.Bounds overviewBounds(double width) {
        return new OverviewStrip.Bounds(PAD_L, PAD_T, Math.max(1, width - PAD_L - PAD_R), OVERVIEW_H);
    }
}
