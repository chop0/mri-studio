package ax.xz.mri.ui.workbench.pane.config;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * Miniature top-down view of the simulation grid. Shows the FOV rectangle,
 * the gridline density, and the slice band.
 */
public final class GeometryPreview extends Canvas {
    private static final Color BG = Color.web("#f8fafc");
    private static final Color FOV_FILL = Color.web("#eef2ff");
    private static final Color FOV_BORDER = Color.web("#6366f1");
    private static final Color GRID_LINE = Color.web("#c7d2fe");
    private static final Color SLICE_BAND = Color.web("rgba(37, 99, 235, 0.15)");
    private static final Color SLICE_LINE = Color.web("#2563eb");
    private static final Color LABEL = Color.web("#475569");
    private static final Color LABEL_MUTED = Color.web("#94a3b8");

    private double fovZMm = 20;
    private double fovRMm = 30;
    private int nZ = 50;
    private int nR = 5;
    private double sliceHalfMm = 5;

    public GeometryPreview() {
        super(0, 0);
        widthProperty().addListener((obs, o, n) -> redraw());
        heightProperty().addListener((obs, o, n) -> redraw());
    }

    @Override public boolean isResizable() { return true; }
    @Override public double prefWidth(double h) { return 240; }
    @Override public double prefHeight(double w) { return 180; }

    public void setGeometry(double fovZMm, double fovRMm, int nZ, int nR, double sliceHalfMm) {
        this.fovZMm = Math.max(0.1, fovZMm);
        this.fovRMm = Math.max(0.1, fovRMm);
        this.nZ = Math.max(2, nZ);
        this.nR = Math.max(2, nR);
        this.sliceHalfMm = Math.max(0, sliceHalfMm);
        redraw();
    }

    private void redraw() {
        double w = getWidth();
        double h = getHeight();
        if (w < 4 || h < 4) return;

        GraphicsContext g = getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        g.setFill(BG);
        g.fillRect(0, 0, w, h);

        double padL = 32, padR = 12, padT = 12, padB = 22;
        double plotW = w - padL - padR;
        double plotH = h - padT - padB;

        // Map Z (horizontal axis: -fovZ/2 .. fovZ/2) and R (vertical: 0 .. fovR)
        // Fit preserving aspect ratio.
        double dataW = fovZMm;
        double dataH = fovRMm;
        double scale = Math.min(plotW / dataW, plotH / dataH);
        double drawW = dataW * scale;
        double drawH = dataH * scale;
        double cx = padL + (plotW - drawW) / 2;
        double cy = padT + (plotH - drawH) / 2;

        // FOV fill
        g.setFill(FOV_FILL);
        g.fillRect(cx, cy, drawW, drawH);
        g.setStroke(FOV_BORDER);
        g.setLineWidth(1);
        g.strokeRect(cx + 0.5, cy + 0.5, drawW - 1, drawH - 1);

        // Grid lines (thin, decimated if too dense)
        int stepZ = Math.max(1, nZ / 20);
        int stepR = Math.max(1, nR / 10);
        g.setStroke(GRID_LINE);
        g.setLineWidth(0.5);
        for (int i = 0; i < nZ; i += stepZ) {
            double px = cx + (i / (double) (nZ - 1)) * drawW;
            g.strokeLine(px, cy, px, cy + drawH);
        }
        for (int i = 0; i < nR; i += stepR) {
            double py = cy + (i / (double) (nR - 1)) * drawH;
            g.strokeLine(cx, py, cx + drawW, py);
        }

        // Slice band — strip around z = 0 of half-thickness sliceHalfMm.
        double halfW = sliceHalfMm / dataW * drawW;
        double sliceX = cx + drawW / 2 - halfW;
        g.setFill(SLICE_BAND);
        g.fillRect(sliceX, cy, 2 * halfW, drawH);
        g.setStroke(SLICE_LINE);
        g.setLineWidth(1);
        double zeroX = cx + drawW / 2;
        g.strokeLine(zeroX, cy, zeroX, cy + drawH);

        // Axis labels
        Font labelFont = Font.font("System", FontWeight.SEMI_BOLD, 10);
        g.setFont(labelFont);
        g.setFill(LABEL);
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText("z", cx + drawW / 2, h - 6);
        g.save();
        g.translate(padL - 18, cy + drawH / 2);
        g.rotate(-90);
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText("r", 0, 4);
        g.restore();

        // Corner labels with extents
        g.setFill(LABEL_MUTED);
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText(String.format("%.0f\u00d7%.0f mm", fovZMm, fovRMm), padL, padT + 2);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText(String.format("%d\u00d7%d", nZ, nR), padL + plotW, padT + 2);
    }
}
