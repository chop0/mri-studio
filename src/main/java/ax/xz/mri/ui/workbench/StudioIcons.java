package ax.xz.mri.ui.workbench;

import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/** Small hand-drawn IDE-style icons without an external asset dependency. */
public final class StudioIcons {
    private static final Color INK = Color.web("#4c6278");
    private static final Color ACCENT = Color.web("#1976d2");
    private static final Color SOFT = Color.web("#dbe7f3");
    private static final Color SEQUENCE_TINT = Color.web("#2e7d32");
    private static final Color HARDWARE_TINT = Color.web("#9333ea");
    private static final Color RUN_TINT = Color.web("#147a3f");

    private StudioIcons() {}

    public static Node create(StudioIconKind kind) {
        var canvas = new Canvas(14, 14);
        var g = canvas.getGraphicsContext2D();
        g.setLineWidth(1.1);
        g.setStroke(INK);
        g.setFill(SOFT);
        switch (kind) {
            case PROJECT -> drawFolder(g, INK);
            case GROUP_SEQUENCES -> drawFolder(g, SEQUENCE_TINT);
            case SEQUENCE -> drawTimeline(g);
            case SIMULATION -> drawSimulation(g);
            case EIGENFIELD -> drawEigenfield(g);
            case MESSAGES -> drawMessages(g);
            case HARDWARE -> drawHardware(g);
            case UNDO -> drawUndo(g);
            case REDO -> drawRedo(g);
            case RUN -> drawRun(g);
            case OUTPUTS -> drawOutputs(g);
            case SAVE -> drawSave(g);
        }
        return canvas;
    }

    private static void drawFolder(GraphicsContext g, Color tint) {
        g.setFill(SOFT);
        g.fillRoundRect(1.5, 4, 11, 7.5, 2, 2);
        g.setStroke(tint);
        g.strokeRoundRect(1.5, 4, 11, 7.5, 2, 2);
        g.setFill(tint.deriveColor(0, 1, 1, 0.25));
        g.fillRoundRect(2.2, 2.5, 4.2, 2.6, 1.4, 1.4);
        g.setStroke(tint);
        g.strokeRoundRect(2.2, 2.5, 4.2, 2.6, 1.4, 1.4);
        g.setStroke(INK);
    }

    private static void drawTimeline(GraphicsContext g) {
        g.strokeLine(1.6, 10.5, 12.2, 10.5);
        for (int i = 0; i < 4; i++) {
            double x = 2.2 + i * 2.5;
            g.setFill(i % 2 == 0 ? ACCENT.deriveColor(0, 1, 1, 0.65) : SOFT);
            g.fillRoundRect(x, 3.2, 1.8, 5.8, 1, 1);
            g.strokeRoundRect(x, 3.2, 1.8, 5.8, 1, 1);
        }
    }

    private static void drawSimulation(GraphicsContext g) {
        g.strokeOval(2.3, 4.0, 9.0, 5.0);
        g.strokeOval(4.2, 2.1, 5.2, 8.8);
        g.strokeOval(3.0, 2.6, 7.6, 7.8);
        g.setFill(ACCENT);
        g.fillOval(6.0, 5.8, 1.8, 1.8);
        g.setFill(SOFT);
    }

    private static void drawMessages(GraphicsContext g) {
        g.setFill(SOFT);
        g.fillRoundRect(1.5, 2.5, 11, 7.5, 2.4, 2.4);
        g.strokeRoundRect(1.5, 2.5, 11, 7.5, 2.4, 2.4);
        g.fillPolygon(
            new double[]{4.2, 6.2, 4.2},
            new double[]{10.0, 10.0, 12.2},
            3);
        g.strokePolygon(
            new double[]{4.2, 6.2, 4.2},
            new double[]{10.0, 10.0, 12.2},
            3);
        g.setStroke(ACCENT);
        g.strokeLine(3.6, 5.1, 10.4, 5.1);
        g.strokeLine(3.6, 6.7, 10.4, 6.7);
        g.strokeLine(3.6, 8.3, 8.4, 8.3);
        g.setStroke(INK);
    }

    private static void drawEigenfield(GraphicsContext g) {
        g.setStroke(ACCENT);
        g.setFill(ACCENT);
        g.fillOval(5.8, 5.8, 2.2, 2.2);
        g.strokeLine(7.0, 4.5, 7.0, 1.8);
        g.strokeLine(7.0, 9.3, 7.0, 12.0);
        g.strokeLine(4.5, 7.0, 1.8, 7.0);
        g.strokeLine(9.3, 7.0, 12.0, 7.0);
        g.setStroke(INK);
        g.setFill(SOFT);
    }

    /** Chip silhouette: a square with leg pins on each side. */
    private static void drawHardware(GraphicsContext g) {
        g.setFill(SOFT);
        g.fillRoundRect(3.5, 3.5, 7, 7, 1.2, 1.2);
        g.setStroke(HARDWARE_TINT);
        g.strokeRoundRect(3.5, 3.5, 7, 7, 1.2, 1.2);
        // Pin legs
        for (int i = 0; i < 3; i++) {
            double t = 4.6 + i * 2.0;
            g.strokeLine(t, 1.8, t, 3.5);     // top
            g.strokeLine(t, 10.5, t, 12.2);   // bottom
            g.strokeLine(1.8, t, 3.5, t);     // left
            g.strokeLine(10.5, t, 12.2, t);   // right
        }
        // Centre dot
        g.setFill(HARDWARE_TINT);
        g.fillOval(6.4, 6.4, 1.4, 1.4);
        g.setStroke(INK);
        g.setFill(SOFT);
    }

    /** Curved arrow returning anti-clockwise. */
    private static void drawUndo(GraphicsContext g) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeArc(2.5, 3.0, 9.0, 8.0, 30, 200, javafx.scene.shape.ArcType.OPEN);
        g.strokeLine(2.6, 6.4, 4.4, 4.5);
        g.strokeLine(2.6, 6.4, 5.0, 7.4);
        g.setLineWidth(1.1);
    }

    /** Curved arrow returning clockwise. */
    private static void drawRedo(GraphicsContext g) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeArc(2.5, 3.0, 9.0, 8.0, -50, 200, javafx.scene.shape.ArcType.OPEN);
        g.strokeLine(11.4, 6.4, 9.6, 4.5);
        g.strokeLine(11.4, 6.4, 9.0, 7.4);
        g.setLineWidth(1.1);
    }

    /** Right-pointing solid triangle. */
    private static void drawRun(GraphicsContext g) {
        g.setFill(RUN_TINT);
        g.fillPolygon(
            new double[]{4.0, 4.0, 11.0},
            new double[]{2.5, 11.5, 7.0},
            3);
        g.setStroke(INK);
        g.strokePolygon(
            new double[]{4.0, 4.0, 11.0},
            new double[]{2.5, 11.5, 7.0},
            3);
    }

    /** Stacked horizontal traces — output / signal lanes. */
    private static void drawOutputs(GraphicsContext g) {
        g.setStroke(ACCENT);
        for (int row = 0; row < 3; row++) {
            double y = 3.5 + row * 3.2;
            g.strokeLine(2.0, y, 12.0, y);
        }
        // Wave on the middle row to suggest signal
        double yMid = 3.5 + 3.2;
        g.setStroke(RUN_TINT);
        g.beginPath();
        g.moveTo(2.0, yMid);
        for (int i = 0; i <= 20; i++) {
            double x = 2.0 + i * 0.5;
            double y = yMid + Math.sin(i * 0.6) * 0.9;
            g.lineTo(x, y);
        }
        g.stroke();
        g.setStroke(INK);
    }

    /** Floppy-style save icon (rounded rect with a cutout slot). */
    private static void drawSave(GraphicsContext g) {
        g.setFill(SOFT);
        g.fillRoundRect(2.0, 2.0, 10.0, 10.0, 1.6, 1.6);
        g.setStroke(INK);
        g.strokeRoundRect(2.0, 2.0, 10.0, 10.0, 1.6, 1.6);
        g.setStroke(ACCENT);
        g.strokeRect(4.0, 7.5, 6.0, 4.0);
        g.strokeRect(4.5, 2.5, 5.0, 2.6);
        g.setStroke(INK);
    }
}
