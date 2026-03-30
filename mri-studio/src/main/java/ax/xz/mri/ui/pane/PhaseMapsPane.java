package ax.xz.mri.ui.pane;

import ax.xz.mri.model.simulation.PhaseMapData;
import ax.xz.mri.state.AppState;
import ax.xz.mri.ui.canvas.ColorUtil;
import ax.xz.mri.ui.framework.CanvasPane;
import ax.xz.mri.ui.framework.ResizableCanvas;
import ax.xz.mri.ui.theme.StudioTheme;
import ax.xz.mri.util.MathUtil;
import javafx.beans.Observable;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

import static ax.xz.mri.ui.theme.StudioTheme.*;

/**
 * Two side-by-side phase heatmaps: phi(z, t) and phi(r, t).
 * Port of {@code drawPhaseMap()} from draw/phaseMap.ts.
 */
public class PhaseMapsPane extends CanvasPane {

    private final ResizableCanvas canvasZ = new ResizableCanvas();
    private final ResizableCanvas canvasR = new ResizableCanvas();

    public PhaseMapsPane(AppState s) {
        super(s);
        var box = new HBox(canvasZ, canvasR);
        HBox.setHgrow(canvasZ, Priority.ALWAYS);
        HBox.setHgrow(canvasR, Priority.ALWAYS);
        setCenter(box);
        canvasZ.setOnResized(this::scheduleRedraw);
        canvasR.setOnResized(this::scheduleRedraw);
        installCursorDrag(canvasZ);
        installCursorDrag(canvasR);
        onAttached();
    }

    @Override public String getPaneId()    { return "phase-maps"; }
    @Override public String getPaneTitle() { return "Phase Maps"; }

    @Override
    protected Observable[] getRedrawTriggers() {
        return new Observable[]{
            appState.computed.phaseMapZ,
            appState.computed.phaseMapR,
            appState.viewport.tS, appState.viewport.tE, appState.viewport.tC,
        };
    }

    @Override
    protected void paint(GraphicsContext g, double w, double h) {
        // parent canvas unused; drawing happens in sub-canvases
    }

    @Override
    protected void scheduleRedraw() {
        super.scheduleRedraw();
        drawPhaseMap(canvasZ, appState.computed.phaseMapZ.get(),
            "\u03c6(z, t)", new double[]{-6, -3, 0, 3, 6}, true);
        drawPhaseMap(canvasR, appState.computed.phaseMapR.get(),
            "\u03c6(r, t)", new double[]{0, 10, 20, 30}, false);
    }

    private void drawPhaseMap(ResizableCanvas cv, PhaseMapData pm, String title,
                               double[] ticks, boolean sliceBounds) {
        double w = cv.getWidth(), h = cv.getHeight();
        if (w <= 0 || h <= 0) return;
        var g = cv.getGraphicsContext2D();
        g.setFill(BG); g.fillRect(0, 0, w, h);
        if (pm == null) return;

        double tMin  = appState.viewport.tS.get();
        double tMax  = Math.max(appState.viewport.tE.get(), tMin + 1);
        double tSpan = tMax - tMin;
        double pad_l = 34, pad_r = 4, pad_t = 14, pad_b = 16;
        double pW    = w - pad_l - pad_r;
        double pH    = h - pad_t - pad_b;

        // Title
        g.setFill(TX); g.setFont(UI_BOLD_9); g.setTextAlign(TextAlignment.CENTER);
        g.fillText(title, pad_l + pW / 2, pad_t - 3);
        g.setTextAlign(TextAlignment.LEFT);

        // Y-axis labels + grid
        g.setFont(UI_8); g.setTextAlign(TextAlignment.RIGHT); g.setFill(TX);
        double yMin = pm.yArr()[0], yMax = pm.yArr()[pm.nY() - 1];
        for (double v : ticks) {
            double y = pad_t + pH * (1 - (v - yMin) / (yMax - yMin));
            g.fillText(String.valueOf((int) v), pad_l - 3, y + 2);
            g.setStroke(Color.color(0, 0, 0, 0.06)); g.setLineWidth(0.3);
            g.strokeLine(pad_l, y, pad_l + pW, y);
        }
        g.setTextAlign(TextAlignment.LEFT);

        // Heatmap cells
        double cellH = pH / pm.nY();
        for (int iy = 0; iy < pm.nY(); iy++) {
            var row = pm.data()[iy];
            double y = pad_t + pH - ((iy + 0.5) / pm.nY()) * pH;
            for (int it = 0; it < row.length; it++) {
                var d = row[it];
                if (d.tMicros() < tMin || d.tMicros() > tMax) continue;
                double xPos  = pad_l + (d.tMicros() - tMin) / tSpan * pW;
                double nextT = (it + 1 < row.length) ? row[it + 1].tMicros() : d.tMicros() + 40;
                double cellW = Math.max(1, (nextT - d.tMicros()) / tSpan * pW + 1);
                g.setFill(ColorUtil.hue2color(d.phaseDeg(), MathUtil.clamp(d.mPerp(), 0, 1)));
                g.fillRect(xPos, y - cellH / 2, cellW, cellH + 1);
            }
        }

        // Slice-boundary dashed lines (z heatmap only)
        if (sliceBounds && appState.document.blochData.get() != null) {
            var f  = appState.document.blochData.get().field();
            double sh = (f.sliceHalf != null ? f.sliceHalf : 0.005) * 1e3;
            for (double zv : new double[]{-sh, sh}) {
                double y = pad_t + pH * (1 - (zv - yMin) / (yMax - yMin));
                g.setStroke(Color.web("#2e7d32")); g.setGlobalAlpha(0.4);
                g.setLineWidth(0.5); g.setLineDashes(3, 3);
                g.strokeLine(pad_l, y, pad_l + pW, y);
            }
            g.setLineDashes(); g.setGlobalAlpha(1);
        }

        // Cursor + triangle handle
        double xC = pad_l + (appState.viewport.tC.get() - tMin) / tSpan * pW;
        g.setStroke(CUR); g.setLineWidth(1.5); g.setGlobalAlpha(0.8);
        g.strokeLine(xC, pad_t, xC, pad_t + pH);
        g.setGlobalAlpha(1);
        g.setFill(CUR); g.setGlobalAlpha(0.7);
        g.fillPolygon(new double[]{xC, xC - 4, xC + 4},
                      new double[]{pad_t + pH, pad_t + pH + 6, pad_t + pH + 6}, 3);
        g.setGlobalAlpha(1);

        // X-axis ticks
        int tickStep = niceTick(tSpan);
        g.setFill(TX2); g.setFont(UI_7); g.setTextAlign(TextAlignment.CENTER); g.setGlobalAlpha(0.6);
        for (double t = Math.ceil(tMin / tickStep) * tickStep; t <= tMax; t += tickStep) {
            double px = pad_l + (t - tMin) / tSpan * pW;
            if (px > pad_l + 4 && px < pad_l + pW - 4) {
                String lbl = tSpan > 2000
                    ? String.format("%.0fms", t / 1000) : (int) t + "\u03bcs";
                g.fillText(lbl, px, h - 2);
            }
        }
        g.setTextAlign(TextAlignment.LEFT); g.setGlobalAlpha(1);
    }

    private void installCursorDrag(ResizableCanvas cv) {
        cv.setOnMousePressed(e  -> moveCursor(cv, e.getX()));
        cv.setOnMouseDragged(e  -> moveCursor(cv, e.getX()));
    }

    private void moveCursor(ResizableCanvas cv, double mouseX) {
        double tMin = appState.viewport.tS.get();
        double tMax = Math.max(appState.viewport.tE.get(), tMin + 1);
        double pad_l = 34;
        double pW    = cv.getWidth() - pad_l - 4;
        double t     = tMin + (mouseX - pad_l) / pW * (tMax - tMin);
        appState.viewport.tC.set(MathUtil.clamp(t, tMin, tMax));
    }

    private static int niceTick(double span) {
        return span > 5000 ? 2000 : span > 2000 ? 1000 : span > 800 ? 200 : span > 300 ? 100 : 50;
    }
}
