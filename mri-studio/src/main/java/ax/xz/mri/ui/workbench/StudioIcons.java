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
    private static final Color IMPORT_TINT = Color.web("#7b1fa2");
    private static final Color SEQUENCE_TINT = Color.web("#2e7d32");

    private StudioIcons() {
    }

    public static Node create(StudioIconKind kind) {
        var canvas = new Canvas(14, 14);
        var g = canvas.getGraphicsContext2D();
        g.setLineWidth(1.1);
        g.setStroke(INK);
        g.setFill(SOFT);
        switch (kind) {
            case PROJECT -> drawFolder(g, INK);
            case GROUP_IMPORTS -> drawFolder(g, IMPORT_TINT);
            case GROUP_SEQUENCES -> drawFolder(g, SEQUENCE_TINT);
            case IMPORT -> drawDocument(g, true);
            case SCENARIO -> drawLayers(g);
            case RUN -> drawActivity(g);
            case CAPTURE -> drawWave(g);
            case SNAPSHOT -> drawCamera(g);
            case SEQUENCE -> drawTimeline(g);
            case SIMULATION -> drawSimulation(g);
            case OPTIMISATION_CONFIG -> drawSliders(g);
            case BOOKMARK -> drawPin(g);
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

    private static void drawDocument(GraphicsContext g, boolean accent) {
        g.fillRoundRect(2.3, 1.5, 9, 11, 2, 2);
        g.strokeRoundRect(2.3, 1.5, 9, 11, 2, 2);
        if (accent) {
            g.setStroke(ACCENT);
            g.strokeLine(4.2, 5, 9.5, 5);
            g.strokeLine(4.2, 7.3, 9.5, 7.3);
            g.strokeLine(4.2, 9.6, 8.1, 9.6);
            g.setStroke(INK);
        }
    }

    private static void drawLayers(GraphicsContext g) {
        g.strokeRoundRect(2.5, 2.2, 7.5, 3.8, 1.2, 1.2);
        g.strokeRoundRect(3.5, 5.2, 7.5, 3.8, 1.2, 1.2);
        g.strokeRoundRect(4.5, 8.2, 7.5, 3.2, 1.2, 1.2);
    }

    private static void drawActivity(GraphicsContext g) {
        g.setStroke(ACCENT);
        g.strokePolyline(
            new double[]{1.5, 4.0, 5.2, 7.0, 8.4, 10.2, 12.0},
            new double[]{8.0, 8.0, 3.0, 10.0, 5.0, 6.8, 6.8},
            7
        );
        g.setStroke(INK);
    }

    private static void drawWave(GraphicsContext g) {
        g.setStroke(ACCENT);
        g.strokePolyline(
            new double[]{1.3, 3.2, 4.8, 6.3, 8.1, 9.8, 12.0},
            new double[]{8.0, 8.0, 3.4, 10.2, 5.6, 8.2, 8.2},
            7
        );
        g.setStroke(INK);
    }

    private static void drawCamera(GraphicsContext g) {
        g.strokeRoundRect(2, 4.2, 10, 6.2, 2, 2);
        g.strokeOval(5.1, 5.2, 3.8, 3.8);
        g.setFill(ACCENT);
        g.fillRect(4.1, 3.0, 2.3, 1.6);
        g.setFill(SOFT);
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

    private static void drawSliders(GraphicsContext g) {
        g.strokeLine(2.2, 3.2, 11.4, 3.2);
        g.strokeLine(2.2, 7.0, 11.4, 7.0);
        g.strokeLine(2.2, 10.8, 11.4, 10.8);
        g.setFill(ACCENT);
        g.fillOval(4.0, 1.9, 2.6, 2.6);
        g.fillOval(8.4, 5.7, 2.6, 2.6);
        g.fillOval(6.2, 9.5, 2.6, 2.6);
        g.setFill(SOFT);
    }

    private static void drawPin(GraphicsContext g) {
        g.setStroke(ACCENT);
        g.strokeOval(3.2, 2.0, 5.2, 5.2);
        g.strokeLine(5.8, 7.2, 5.8, 11.3);
        g.strokeLine(5.8, 11.3, 4.2, 12.5);
        g.setStroke(INK);
    }
}
