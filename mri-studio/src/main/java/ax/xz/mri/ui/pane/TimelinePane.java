package ax.xz.mri.ui.pane;

import ax.xz.mri.model.field.FieldMap;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.state.AppState;
import ax.xz.mri.ui.framework.CanvasPane;
import ax.xz.mri.ui.theme.StudioTheme;
import ax.xz.mri.util.MathUtil;
import javafx.beans.Observable;
import javafx.scene.Cursor;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;

import static ax.xz.mri.ui.theme.StudioTheme.*;

/**
 * Waveform timeline: |B₁|, Gz, Gx, and signal tracks with draggable window handles.
 * Port of {@code drawTimeline()} from draw/timeline.ts.
 */
public class TimelinePane extends CanvasPane {

    record SegBounds(double t0, double tE) {}
    record RfWindow(double t0, double t1) {}

    private static final double PAD_L = 40, PAD_R = 6, PAD_T = 2, PAD_B = 12;

    // Drag state: 0=none, 1=tS, 2=tE, 3=tC, 4=pan
    private int    dragMode = 0;
    private double dragStartX, dragStartVS, dragStartVE;

    public TimelinePane(AppState s) {
        super(s);
        installMouseHandlers();
        onAttached();
    }

    @Override public String getPaneId()    { return "timeline"; }
    @Override public String getPaneTitle() { return "Timeline"; }

    @Override
    protected Observable[] getRedrawTriggers() {
        return new Observable[]{
            appState.document.currentPulse,
            appState.computed.signalTrace,
            appState.viewport.tS, appState.viewport.tE,
            appState.viewport.vS, appState.viewport.vE,
            appState.viewport.tC,
        };
    }

    @Override
    protected void installMouseHandlers() {
        canvas.setOnMousePressed(e -> {
            double x = e.getX();
            double tS = tPx(appState.viewport.tS.get());
            double tE = tPx(appState.viewport.tE.get());
            double tC = tPx(appState.viewport.tC.get());
            if (Math.abs(x - tC) < 6)       dragMode = 3;
            else if (Math.abs(x - tS) < 6)  dragMode = 1;
            else if (Math.abs(x - tE) < 6)  dragMode = 2;
            else                             { dragMode = 4; dragStartX = x;
                                              dragStartVS = appState.viewport.vS.get();
                                              dragStartVE = appState.viewport.vE.get(); }
        });
        canvas.setOnMouseDragged(e -> {
            double t = pixToTime(e.getX());
            switch (dragMode) {
                case 1 -> appState.viewport.tS.set(MathUtil.clamp(t,
                    appState.viewport.vS.get(), appState.viewport.tE.get()));
                case 2 -> appState.viewport.tE.set(MathUtil.clamp(t,
                    appState.viewport.tS.get(), appState.viewport.vE.get()));
                case 3 -> appState.viewport.tC.set(MathUtil.clamp(t,
                    appState.viewport.vS.get(), appState.viewport.vE.get()));
                case 4 -> {
                    double span  = dragStartVE - dragStartVS;
                    double delta = pixToTime(dragStartX) - pixToTime(e.getX());
                    double ns    = dragStartVS + delta;
                    double max   = appState.viewport.maxTime.get();
                    ns = MathUtil.clamp(ns, 0, max - span);
                    appState.viewport.vS.set(ns);
                    appState.viewport.vE.set(ns + span);
                }
            }
            updateCursor(e.getX());
        });
        canvas.setOnMouseReleased(e -> { dragMode = 0; canvas.setCursor(Cursor.DEFAULT); });
        canvas.setOnMouseMoved(e -> updateCursor(e.getX()));
        canvas.setOnScroll(e -> {
            double vS = appState.viewport.vS.get(), vE = appState.viewport.vE.get();
            double span = vE - vS;
            double factor = e.getDeltaY() > 0 ? 0.8 : 1.25;
            double centre = pixToTime(e.getX());
            double ns = centre - (centre - vS) * factor;
            double ne = centre + (vE - centre) * factor;
            double max = appState.viewport.maxTime.get();
            ns = MathUtil.clamp(ns, 0, max);
            ne = MathUtil.clamp(ne, ns + 1, max);
            appState.viewport.vS.set(ns);
            appState.viewport.vE.set(ne);
        });
    }

    private void updateCursor(double x) {
        double tS = tPx(appState.viewport.tS.get());
        double tE = tPx(appState.viewport.tE.get());
        double tC = tPx(appState.viewport.tC.get());
        if (Math.abs(x - tS) < 6 || Math.abs(x - tE) < 6)
            canvas.setCursor(Cursor.H_RESIZE);
        else if (Math.abs(x - tC) < 6)
            canvas.setCursor(Cursor.H_RESIZE);
        else
            canvas.setCursor(Cursor.OPEN_HAND);
    }

    @Override
    protected void paint(GraphicsContext g, double w, double h) {
        var pulse = appState.document.currentPulse.get();
        if (pulse == null || appState.document.blochData.get() == null) {
            g.setFill(BG); g.fillRect(0, 0, w, h); return;
        }
        var f = appState.document.blochData.get().field();

        // Build segment/RF tables
        var segBounds = new ArrayList<SegBounds>();
        var rfWindows = new ArrayList<RfWindow>();
        double tAcc = 0;
        for (int si = 0; si < f.segments.size() && si < pulse.size(); si++) {
            var seg   = f.segments.get(si);
            var steps = pulse.get(si).steps();
            int nTotal = seg.totalSteps();
            segBounds.add(new SegBounds(tAcc, tAcc + nTotal * seg.dt() * 1e6));
            Double rfStart = null;
            double tStep  = tAcc;
            for (var step : steps) {
                if (step.isRfOn() && rfStart == null) rfStart = tStep;
                if (!step.isRfOn() && rfStart != null) {
                    rfWindows.add(new RfWindow(rfStart, tStep));
                    rfStart = null;
                }
                tStep += seg.dt() * 1e6;
            }
            if (rfStart != null) rfWindows.add(new RfWindow(rfStart, tStep));
            tAcc += nTotal * seg.dt() * 1e6;
        }

        double pH = h - PAD_T - PAD_B;
        double pW = w - PAD_L - PAD_R;

        g.setFill(BG); g.fillRect(0, 0, w, h);

        // Signal max for scaling
        double sigMax = 1e-6;
        var sigTrace = appState.computed.signalTrace.get();
        if (sigTrace != null)
            for (var pt : sigTrace.points()) sigMax = Math.max(sigMax, pt.signal());

        String[] labels = {"|B₁|", "Gz", "Gx", "Sig"};
        Color[]  colours= {Color.web("#f59e0b"), Color.web("#3b82f6"), Color.web("#ef4444"), Color.web("#22c55e")};
        boolean[] centred = {false, true, true, false};
        double[]  maxVals = {250e-6, 0.035, 0.035, sigMax};

        double tH = pH / 4.0;
        double vS = appState.viewport.vS.get(), vE = appState.viewport.vE.get();
        double vSpan = vE - vS;

        for (int ti = 0; ti < 4; ti++) {
            double y0 = PAD_T + ti * tH;

            g.setFill(ti % 2 == 1 ? BG2 : BG);
            g.fillRect(PAD_L, y0, pW, tH);

            g.setStroke(withAlpha(GR, 1)); g.setLineWidth(0.5);
            g.strokeLine(PAD_L, y0 + tH, PAD_L + pW, y0 + tH);

            g.setFill(TX2); g.setFont(MONO_BOLD_7); g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(labels[ti], PAD_L - 4, y0 + tH / 2 + 3);
            g.setTextAlign(TextAlignment.LEFT);

            if (centred[ti]) {
                g.setStroke(Color.color(1, 1, 1, 0.04)); g.setLineWidth(0.5);
                g.strokeLine(PAD_L, y0 + tH / 2, PAD_L + pW, y0 + tH / 2);
            }

            // RF shading
            for (var rf : rfWindows) {
                if (rf.t1() < vS || rf.t0() > vE) continue;
                double xF = Math.max(PAD_L, tPx(rf.t0()));
                double xE = Math.min(PAD_L + pW, tPx(rf.t1()));
                g.setFill(Color.color(1, 1, 1, 0.02));
                g.fillRect(xF, y0, xE - xF, tH);
            }

            // Segment dividers
            for (int si = 1; si < segBounds.size(); si++) {
                double t0 = segBounds.get(si).t0();
                if (t0 < vS || t0 > vE) continue;
                double xD = tPx(t0);
                g.setStroke(Color.color(1, 1, 1, 0.06)); g.setLineWidth(0.5);
                g.strokeLine(xD, y0, xD, y0 + tH);
            }

            // Waveform path (clipped)
            g.save(); g.beginPath(); g.rect(PAD_L, y0, pW, tH); g.clip();
            g.beginPath(); g.setStroke(colours[ti]); g.setLineWidth(1.2); g.setGlobalAlpha(0.8);
            boolean started = false;
            if (ti == 3) { // signal
                if (sigTrace != null) for (var pt : sigTrace.points()) {
                    if (pt.tMicros() < vS - vSpan * 0.01 || pt.tMicros() > vE + vSpan * 0.01) continue;
                    double px = tPx(pt.tMicros());
                    double py = y0 + tH - (pt.signal() / maxVals[ti]) * tH * 0.85;
                    if (!started) { g.moveTo(px, py); started = true; } else g.lineTo(px, py);
                }
            } else {
                for (int si = 0; si < f.segments.size() && si < pulse.size(); si++) {
                    var seg   = f.segments.get(si);
                    var steps = pulse.get(si).steps();
                    double t  = segBounds.get(si).t0();
                    for (var step : steps) {
                        if (t >= vS - vSpan * 0.01 && t <= vE + vSpan * 0.01) {
                            double v = switch (ti) {
                                case 0 -> step.b1Magnitude();
                                case 1 -> step.gz();
                                default-> step.gx();
                            };
                            double px = tPx(t);
                            double py = centred[ti]
                                ? y0 + tH / 2 - (v / maxVals[ti]) * tH / 2
                                : y0 + tH - (v / maxVals[ti]) * tH * 0.85;
                            if (!started) { g.moveTo(px, py); started = true; } else g.lineTo(px, py);
                        }
                        t += seg.dt() * 1e6;
                    }
                }
            }
            g.stroke(); g.setGlobalAlpha(1); g.restore();
        }

        // Time-window highlight
        double xS = MathUtil.clamp(tPx(appState.viewport.tS.get()), PAD_L, PAD_L + pW);
        double xE = MathUtil.clamp(tPx(appState.viewport.tE.get()), PAD_L, PAD_L + pW);
        g.setFill(AC); g.setGlobalAlpha(0.06);
        g.fillRect(xS, PAD_T, xE - xS, pH);
        g.setGlobalAlpha(1);
        for (double xh : new double[]{xS, xE}) {
            g.setFill(AC); g.setGlobalAlpha(0.7);
            g.fillRect(xh - 1.5, PAD_T, 3, pH);
            g.setGlobalAlpha(1);
        }

        // Cursor line
        double xC = MathUtil.clamp(tPx(appState.viewport.tC.get()), PAD_L, PAD_L + pW);
        g.setStroke(CUR); g.setLineWidth(1.5); g.setGlobalAlpha(0.8);
        g.strokeLine(xC, PAD_T, xC, PAD_T + pH);
        g.setGlobalAlpha(1);

        // Segment index labels
        g.setFont(MONO_BOLD_7); g.setTextAlign(TextAlignment.CENTER);
        g.setFill(TX2); g.setGlobalAlpha(0.3);
        for (int si = 0; si < segBounds.size(); si++) {
            var sb = segBounds.get(si);
            if (sb.tE() < vS || sb.t0() > vE) continue;
            g.fillText(String.valueOf(si), (tPx(sb.t0()) + tPx(sb.tE())) / 2, PAD_T + 8);
        }
        g.setTextAlign(TextAlignment.LEFT); g.setGlobalAlpha(1);

        // Time axis ticks
        int tickStep = niceTick(vSpan);
        g.setFill(TX2); g.setFont(MONO_7); g.setTextAlign(TextAlignment.CENTER); g.setGlobalAlpha(0.4);
        for (double t = Math.ceil(vS / tickStep) * tickStep; t <= vE; t += tickStep) {
            double px = tPx(t);
            if (px > PAD_L + 4 && px < PAD_L + pW - 4) {
                g.fillText(String.format("%.0fms", t / 1000), px, h - 2);
            }
        }
        g.setTextAlign(TextAlignment.LEFT); g.setGlobalAlpha(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private double tPx(double t) {
        double vS = appState.viewport.vS.get(), vE = appState.viewport.vE.get();
        double pW = canvas.getWidth() - PAD_L - PAD_R;
        return PAD_L + (t - vS) / (vE - vS) * pW;
    }

    private double pixToTime(double px) {
        double vS = appState.viewport.vS.get(), vE = appState.viewport.vE.get();
        double pW = canvas.getWidth() - PAD_L - PAD_R;
        return vS + (px - PAD_L) / pW * (vE - vS);
    }

    private static int niceTick(double span) {
        return span > 5000 ? 2000 : span > 2000 ? 1000 : span > 800 ? 500 : 200;
    }
}
