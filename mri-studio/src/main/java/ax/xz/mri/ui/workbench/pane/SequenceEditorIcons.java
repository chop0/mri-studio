package ax.xz.mri.ui.workbench.pane;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/** 24x24 hand-drawn icons for the sequence editor tool palette. */
public final class SequenceEditorIcons {
    private static final Color INK = Color.web("#4c6278");
    private static final Color ACCENT = Color.web("#1976d2");
    private static final Color GREEN = Color.web("#2e7d32");
    private static final Color PURPLE = Color.web("#7b1fa2");
    private static final Color ORANGE = Color.web("#ff6f00");
    private static final Color RED = Color.web("#c62828");
    private static final Color SOFT = Color.web("#dbe7f3");

    private SequenceEditorIcons() {}

    public static Canvas create(SequenceToolKind kind) {
        var canvas = new Canvas(24, 24);
        var g = canvas.getGraphicsContext2D();
        g.setLineWidth(1.6);
        g.setStroke(INK);
        g.setFill(SOFT);
        switch (kind) {
            case SELECT -> drawPointer(g);
            case CONSTANT -> drawRect(g);
            case SINC -> drawSinc(g);
            case TRAPEZOID -> drawTrapezoid(g);
            case GAUSSIAN -> drawGaussian(g);
            case SPLINE -> drawSpline(g);
            case TRIANGLE -> drawTriangle(g);
            case DELETE_CLIP -> drawDelete(g);
            case DUPLICATE_CLIP -> drawDuplicate(g);
            case SPOILER -> drawSpoiler(g);
            case REFOCUS -> drawRefocus(g);
            case SLICE_SELECT -> drawSliceSelect(g);
            case READOUT -> drawReadout(g);
            case CONSTRAINTS -> drawConstraints(g);
        }
        return canvas;
    }

    private static void drawPointer(GraphicsContext g) {
        // Arrow cursor icon
        g.setStroke(INK);
        g.setFill(SOFT);
        g.setLineWidth(1.4);
        double[] xs = {6, 6, 10, 11, 14, 12, 18};
        double[] ys = {3, 19, 15, 19, 19, 14, 8};
        g.fillPolygon(xs, ys, 7);
        g.strokePolygon(xs, ys, 7);
    }

    private static void drawSpline(GraphicsContext g) {
        g.setStroke(ACCENT);
        g.setLineWidth(1.6);
        // Curved line through control points
        double[] xs = new double[20];
        double[] ys = new double[20];
        for (int i = 0; i < 20; i++) {
            double t = i / 19.0;
            // S-curve shape
            double val = 0.5 + 0.5 * Math.sin((t - 0.5) * Math.PI);
            xs[i] = 2 + t * 20;
            ys[i] = 20 - val * 16;
        }
        g.strokePolyline(xs, ys, 20);
        // Control point dots
        g.setFill(ACCENT);
        g.fillOval(4, 17, 4, 4);
        g.fillOval(10, 10, 4, 4);
        g.fillOval(16, 4, 4, 4);
        g.setStroke(INK);
    }

    private static void drawTriangle(GraphicsContext g) {
        g.setStroke(GREEN);
        g.setLineWidth(1.8);
        g.strokePolyline(
            new double[]{3, 12, 21},
            new double[]{18, 5, 18},
            3
        );
        g.setStroke(INK);
    }

    private static void drawRect(GraphicsContext g) {
        g.setFill(ACCENT.deriveColor(0, 1, 1, 0.3));
        g.fillRect(4, 6, 16, 12);
        g.setStroke(ACCENT);
        g.strokeRect(4, 6, 16, 12);
        g.setStroke(INK);
    }

    private static void drawSinc(GraphicsContext g) {
        g.setStroke(ACCENT);
        g.setLineWidth(1.6);
        double[] xs = new double[20];
        double[] ys = new double[20];
        for (int i = 0; i < 20; i++) {
            double t = (i / 19.0) * 4 * Math.PI - 2 * Math.PI;
            double sinc = Math.abs(t) < 0.01 ? 1.0 : Math.sin(t) / t;
            xs[i] = 2 + i * (20.0 / 19);
            ys[i] = 12 - sinc * 8;
        }
        g.strokePolyline(xs, ys, 20);
        g.setStroke(INK);
    }

    private static void drawTrapezoid(GraphicsContext g) {
        g.setStroke(GREEN);
        g.setLineWidth(1.8);
        g.strokePolyline(
            new double[]{3, 7, 17, 21},
            new double[]{18, 6, 6, 18},
            4
        );
        g.setStroke(INK);
    }

    private static void drawGaussian(GraphicsContext g) {
        g.setStroke(ACCENT);
        g.setLineWidth(1.6);
        double[] xs = new double[20];
        double[] ys = new double[20];
        for (int i = 0; i < 20; i++) {
            double t = (i / 19.0 - 0.5) * 4;
            double gauss = Math.exp(-t * t / 2);
            xs[i] = 2 + i * (20.0 / 19);
            ys[i] = 20 - gauss * 14;
        }
        g.strokePolyline(xs, ys, 20);
        g.setStroke(INK);
    }

    private static void drawInsert(GraphicsContext g) {
        g.setStroke(GREEN);
        g.setLineWidth(2.2);
        g.strokeLine(12, 5, 12, 19);
        g.strokeLine(5, 12, 19, 12);
        g.setStroke(INK);
    }

    private static void drawDelete(GraphicsContext g) {
        g.setStroke(RED);
        g.setLineWidth(2.2);
        g.strokeLine(6, 6, 18, 18);
        g.strokeLine(18, 6, 6, 18);
        g.setStroke(INK);
    }

    private static void drawDuplicate(GraphicsContext g) {
        g.setStroke(INK);
        g.setFill(SOFT);
        g.fillRoundRect(7, 7, 13, 13, 2, 2);
        g.strokeRoundRect(7, 7, 13, 13, 2, 2);
        g.fillRoundRect(4, 4, 13, 13, 2, 2);
        g.strokeRoundRect(4, 4, 13, 13, 2, 2);
    }

    private static void drawSpoiler(GraphicsContext g) {
        g.setStroke(PURPLE);
        g.setLineWidth(1.6);
        g.strokePolyline(
            new double[]{3, 6, 6, 18, 18, 21},
            new double[]{18, 18, 6, 6, 18, 18},
            6
        );
        // Dephasing arrows
        g.setLineWidth(1.2);
        g.strokeLine(10, 10, 14, 10);
        g.strokeLine(12, 8, 14, 10);
        g.strokeLine(12, 12, 14, 10);
        g.setStroke(INK);
    }

    private static void drawRefocus(GraphicsContext g) {
        g.setStroke(ACCENT);
        g.setLineWidth(2.0);
        // 180° symbol
        g.strokeArc(7, 7, 10, 10, 0, 180, javafx.scene.shape.ArcType.OPEN);
        g.setLineWidth(1.4);
        g.strokeLine(7, 12, 7, 20);
        g.strokeLine(17, 12, 17, 20);
        // Crusher gradients
        g.setStroke(PURPLE);
        g.setLineWidth(1.0);
        g.strokeLine(4, 20, 7, 14);
        g.strokeLine(17, 14, 20, 20);
        g.setStroke(INK);
    }

    private static void drawSliceSelect(GraphicsContext g) {
        // RF pulse
        g.setStroke(ACCENT);
        g.setLineWidth(1.4);
        g.strokePolyline(
            new double[]{4, 8, 12, 16, 20},
            new double[]{14, 14, 4, 14, 14},
            5
        );
        // Slice gradient beneath
        g.setStroke(GREEN);
        g.strokePolyline(
            new double[]{4, 6, 18, 20},
            new double[]{20, 16, 16, 20},
            4
        );
        g.setStroke(INK);
    }

    private static void drawReadout(GraphicsContext g) {
        // Gradient line
        g.setStroke(GREEN);
        g.setLineWidth(1.4);
        g.strokePolyline(
            new double[]{3, 5, 19, 21},
            new double[]{14, 8, 8, 14},
            4
        );
        // ADC markers
        g.setStroke(ORANGE);
        g.setLineWidth(1.0);
        for (int i = 0; i < 5; i++) {
            double x = 7 + i * 2.5;
            g.strokeLine(x, 16, x, 20);
        }
        g.setStroke(INK);
    }

    private static void drawConstraints(GraphicsContext g) {
        // Dashed limit lines
        g.setStroke(RED);
        g.setLineWidth(1.0);
        g.setLineDashes(3, 2);
        g.strokeLine(3, 6, 21, 6);
        g.strokeLine(3, 18, 21, 18);
        g.setLineDashes();
        // Waveform between limits
        g.setStroke(ACCENT);
        g.setLineWidth(1.4);
        g.strokePolyline(
            new double[]{4, 8, 12, 16, 20},
            new double[]{12, 8, 14, 10, 12},
            5
        );
        g.setStroke(INK);
    }
}
