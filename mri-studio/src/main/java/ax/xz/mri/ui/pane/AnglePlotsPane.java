package ax.xz.mri.ui.pane;

import ax.xz.mri.state.AppState;
import ax.xz.mri.ui.framework.CanvasPane;
import ax.xz.mri.ui.theme.StudioTheme;
import javafx.beans.Observable;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

import static ax.xz.mri.ui.theme.StudioTheme.*;

/**
 * Three time-domain plots: Phase phi, Polar theta, |M⊥|.
 * Port of {@code drawPlots()} from draw/plots.ts.
 */
public class AnglePlotsPane extends CanvasPane {

    record PlotDef(String title, String unit, double min, double max, double[] ticks,
                   int fn /* 0=phase, 1=polar, 2=mPerp */) {}

    private static final PlotDef[] PLOTS = {
        new PlotDef("Phase \u03c6",  "\u00b0",  -180, 180, new double[]{-180, -90, 0, 90, 180}, 0),
        new PlotDef("Polar \u03b8",  "\u00b0",     0, 180, new double[]{0, 45, 90, 135, 180},   1),
        new PlotDef("|M\u22a5|", "",      0,   1, new double[]{0, 0.25, 0.5, 0.75, 1}, 2),
    };

    public AnglePlotsPane(AppState s) {
        super(s);
        installMouseHandlers();
        onAttached();
    }

    @Override public String getPaneId()    { return "angle-plots"; }
    @Override public String getPaneTitle() { return "Angle Plots"; }

    @Override
    protected Observable[] getRedrawTriggers() {
        return new Observable[]{
            appState.isochromats.isochromats,
            appState.viewport.tS, appState.viewport.tE, appState.viewport.tC,
        };
    }

    @Override
    protected void installMouseHandlers() {
        canvas.setOnMouseClicked(e -> {
            double tMin = appState.viewport.tS.get();
            double tMax = Math.max(appState.viewport.tE.get(), tMin + 1);
            double pad_l = 40;
            double pW    = canvas.getWidth() - pad_l - 8;
            double t     = tMin + (e.getX() - pad_l) / pW * (tMax - tMin);
            appState.viewport.tC.set(Math.max(tMin, Math.min(tMax, t)));
        });
    }

    @Override
    protected void paint(GraphicsContext g, double w, double h) {
        g.setFill(BG); g.fillRect(0, 0, w, h);

        double pad_l = 40, pad_r = 8, pad_t = 14, pad_b = 18, gap = 10;
        double pW   = (w - pad_l - pad_r - gap * 2) / 3;
        double pH   = h - pad_t - pad_b;
        double tMin = appState.viewport.tS.get();
        double tMax = Math.max(appState.viewport.tE.get(), tMin + 1);
        double tSpan = tMax - tMin;
        double tC   = appState.viewport.tC.get();

        var vis = appState.isochromats.isochromats.filtered(iso -> iso.visible() && iso.trajectory() != null);

        for (int pi = 0; pi < PLOTS.length; pi++) {
            var plot = PLOTS[pi];
            double ox = pad_l + pi * (pW + gap);
            double oy = pad_t;

            // Y-grid + labels
            g.setTextAlign(TextAlignment.RIGHT);
            for (double v : plot.ticks()) {
                double y = oy + pH - (v - plot.min()) / (plot.max() - plot.min()) * pH;
                g.setStroke(Color.color(0, 0, 0, v == 0 ? 0.15 : 0.06));
                g.setLineWidth(v == 0 ? 0.6 : 0.3);
                g.strokeLine(ox, y, ox + pW, y);
                g.setFill(TX); g.setFont(UI_8);
                String lbl = "\u00b0".equals(plot.unit()) ? (int) v + "\u00b0"
                             : (v % 1 != 0 ? String.format("%.2f", v) : String.valueOf((int) v));
                g.fillText(lbl, ox - 4, y + 3);
            }
            g.setTextAlign(TextAlignment.LEFT);

            // Axis frame
            g.setStroke(Color.color(0, 0, 0, 0.15)); g.setLineWidth(0.5);
            g.beginPath(); g.moveTo(ox, oy); g.lineTo(ox, oy + pH); g.lineTo(ox + pW, oy + pH); g.stroke();

            // X ticks
            int xTickStep = niceTick(tSpan);
            g.setFill(TX2); g.setFont(UI_7); g.setTextAlign(TextAlignment.CENTER); g.setGlobalAlpha(0.6);
            for (double t = Math.ceil(tMin / xTickStep) * xTickStep; t <= tMax; t += xTickStep) {
                double px = ox + (t - tMin) / tSpan * pW;
                if (px > ox + 4 && px < ox + pW - 4) {
                    String lbl = tSpan > 2000
                        ? String.format("%.0f", t / 1000) + (t % 1000 != 0 ? String.format(".%01.0f", (t % 1000) / 100) : "")
                        : String.valueOf((int) t);
                    g.fillText(lbl, px, oy + pH + 10);
                    g.setStroke(Color.color(0, 0, 0, 0.04)); g.setLineWidth(0.3);
                    g.strokeLine(px, oy, px, oy + pH);
                }
            }
            if (pi == 2) {
                g.setTextAlign(TextAlignment.RIGHT);
                g.fillText(tSpan > 2000 ? "ms" : "\u03bcs", ox + pW, oy + pH + 10);
                g.setTextAlign(TextAlignment.LEFT);
            }
            g.setGlobalAlpha(1);

            // Cursor line
            double xC = ox + (tC - tMin) / tSpan * pW;
            g.setStroke(CUR); g.setLineWidth(1); g.setGlobalAlpha(0.5);
            g.strokeLine(xC, oy, xC, oy + pH);
            g.setGlobalAlpha(1);

            // Title
            g.setFill(TX); g.setFont(UI_BOLD_10); g.setTextAlign(TextAlignment.CENTER);
            g.fillText(plot.title(), ox + pW / 2, oy - 3);
            g.setTextAlign(TextAlignment.LEFT);

            // Traces (clipped)
            g.save(); g.beginPath(); g.rect(ox, oy - 1, pW, pH + 2); g.clip();
            for (var iso : vis) {
                var traj = iso.trajectory();
                g.setStroke(iso.colour()); g.setLineWidth(1.2); g.setGlobalAlpha(0.85);
                g.beginPath();
                boolean started = false;
                for (int i = 0; i < traj.pointCount(); i += 5) {
                    double t = traj.tAt(i);
                    if (t < tMin - tSpan * 0.02 || t > tMax + tSpan * 0.02) continue;
                    double v = evalPlot(plot, traj.mxAt(i), traj.myAt(i), traj.mzAt(i));
                    if (Double.isNaN(v)) { started = false; continue; }
                    double px = ox + (t - tMin) / tSpan * pW;
                    double py = oy + pH - (v - plot.min()) / (plot.max() - plot.min()) * pH;
                    if (!started) { g.moveTo(px, py); started = true; } else g.lineTo(px, py);
                }
                g.stroke(); g.setGlobalAlpha(1);

                // Cursor dot
                var sa = traj.interpolateAt(tC);
                if (sa != null) {
                    double v = evalPlot(plot, sa.mx(), sa.my(), sa.mz());
                    if (!Double.isNaN(v)) {
                        g.setFill(iso.colour());
                        double py = oy + pH - (v - plot.min()) / (plot.max() - plot.min()) * pH;
                        g.fillOval(xC - 3, py - 3, 6, 6);
                    }
                }
            }
            g.restore();
        }
    }

    private static double evalPlot(PlotDef p, double mx, double my, double mz) {
        return switch (p.fn()) {
            case 0 -> {
                double m = Math.sqrt(mx * mx + my * my);
                yield m > 0.01 ? Math.atan2(my, mx) * 180.0 / Math.PI : Double.NaN;
            }
            case 1 -> Math.atan2(Math.sqrt(mx * mx + my * my), mz) * 180.0 / Math.PI;
            default-> Math.sqrt(mx * mx + my * my);
        };
    }

    private static int niceTick(double span) {
        return span > 5000 ? 2000 : span > 2000 ? 1000 : span > 800 ? 200 : span > 300 ? 100 : 50;
    }
}
