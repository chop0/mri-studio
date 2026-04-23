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
}
