package ax.xz.mri.ui.workbench.pane.config;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * Renders T₁ / T₂ relaxation curves so the user can visually gauge the effect
 * of the tissue parameters. Redraws on property change.
 */
public final class RelaxationPreview extends Canvas {
    private static final Color BG = Color.web("#f8fafc");
    private static final Color GRID = Color.web("#e5e7eb");
    private static final Color AXIS = Color.web("#94a3b8");
    private static final Color T1_LINE = Color.web("#2563eb");
    private static final Color T2_LINE = Color.web("#dc2626");
    private static final Color LABEL = Color.web("#64748b");

    private double t1Ms = 1000;
    private double t2Ms = 100;

    public RelaxationPreview() {
        super(0, 0);
        widthProperty().addListener((obs, o, n) -> redraw());
        heightProperty().addListener((obs, o, n) -> redraw());
    }

    @Override public boolean isResizable() { return true; }
    @Override public double prefWidth(double h) { return 240; }
    @Override public double prefHeight(double w) { return 140; }

    public void setParams(double t1Ms, double t2Ms) {
        this.t1Ms = t1Ms;
        this.t2Ms = t2Ms;
        redraw();
    }

    private void redraw() {
        double w = getWidth();
        double h = getHeight();
        if (w < 4 || h < 4) return;

        GraphicsContext g = getGraphicsContext2D();
        g.clearRect(0, 0, w, h);

        // Background
        g.setFill(BG);
        g.fillRect(0, 0, w, h);

        double padL = 28, padR = 10, padT = 12, padB = 22;
        double plotW = w - padL - padR;
        double plotH = h - padT - padB;

        // Grid (horizontal lines at 0, 0.5, 1)
        g.setStroke(GRID);
        g.setLineWidth(1);
        for (int i = 0; i <= 2; i++) {
            double y = padT + plotH * (i / 2.0);
            g.strokeLine(padL, y, padL + plotW, y);
        }

        // Axes
        g.setStroke(AXIS);
        g.strokeLine(padL, padT, padL, padT + plotH);
        g.strokeLine(padL, padT + plotH, padL + plotW, padT + plotH);

        // Determine time range — show ~5 × max(T1,T2) so both curves asymptote.
        double tMaxMs = Math.max(t1Ms, t2Ms) * 5;
        if (tMaxMs <= 0) tMaxMs = 1000;

        int steps = Math.max(40, (int) plotW);

        // T1 recovery: Mz(t) = 1 - exp(-t/T1)
        g.setStroke(T1_LINE);
        g.setLineWidth(1.6);
        g.beginPath();
        for (int i = 0; i <= steps; i++) {
            double t = (i / (double) steps) * tMaxMs;
            double y = 1 - Math.exp(-t / Math.max(t1Ms, 1e-6));
            double px = padL + (i / (double) steps) * plotW;
            double py = padT + plotH - y * plotH;
            if (i == 0) g.moveTo(px, py); else g.lineTo(px, py);
        }
        g.stroke();

        // T2 decay: Mxy(t) = exp(-t/T2)
        g.setStroke(T2_LINE);
        g.setLineWidth(1.6);
        g.beginPath();
        for (int i = 0; i <= steps; i++) {
            double t = (i / (double) steps) * tMaxMs;
            double y = Math.exp(-t / Math.max(t2Ms, 1e-6));
            double px = padL + (i / (double) steps) * plotW;
            double py = padT + plotH - y * plotH;
            if (i == 0) g.moveTo(px, py); else g.lineTo(px, py);
        }
        g.stroke();

        // Labels
        Font labelFont = Font.font("System", FontWeight.SEMI_BOLD, 10);
        g.setFont(labelFont);
        g.setFill(LABEL);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText("1", padL - 4, padT + 4);
        g.fillText("0", padL - 4, padT + plotH + 4);
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText("0 ms", padL, h - 6);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText(formatMs(tMaxMs), padL + plotW, h - 6);

        // Legend
        g.setFont(labelFont);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFill(T1_LINE);
        g.fillText("T\u2081", padL + 8, padT + 14);
        g.setFill(T2_LINE);
        g.fillText("T\u2082", padL + 32, padT + 14);
    }

    private static String formatMs(double ms) {
        if (ms >= 1000) return String.format("%.1f s", ms / 1000);
        return String.format("%.0f ms", ms);
    }
}
