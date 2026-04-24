package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * Shared drawing primitives and color constants for component presenters.
 * Keeps every presenter's visual style consistent — ink color, accent
 * colors, the standard ground-tail glyph, and text labels.
 */
public final class SchematicInk {
    public static final Color INK = Color.web("#1f2933");
    public static final Color ACCENT = Color.web("#1976d2");
    public static final Color GATE_ACCENT = Color.web("#d97706");
    public static final Color PROBE_ACCENT = Color.web("#059669");
    public static final Color COIL_ACCENT = Color.web("#7c3aed");
    public static final Color MIXER_ACCENT = Color.web("#b91c1c");

    private SchematicInk() {}

    /**
     * Draws a short lead followed by three tapering ground bars. Used by
     * coils, shunt passives, and anything else with an implicit ground
     * return.
     */
    public static void drawGroundTail(GraphicsContext g, double xStart) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(xStart, 0, xStart + 14, 0);
        double gx = xStart + 14;
        g.strokeLine(gx, -7, gx, 7);
        g.strokeLine(gx + 4, -5, gx + 4, 5);
        g.strokeLine(gx + 8, -3, gx + 8, 3);
    }

    /** Draws a centered label near the component body. */
    public static void drawLabel(GraphicsContext g, String text, Color color, double x, double y) {
        g.setFill(color);
        g.setFont(Font.font("System", 11));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(text, x, y);
    }

    /** Shunt-passive body: lead → boxed glyph → ground tail → name label. */
    public static void drawShuntBody(GraphicsContext g, String name, String glyph) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-45, 0, -14, 0);
        g.setFill(Color.WHITE);
        g.fillRoundRect(-14, -10, 28, 20, 4, 4);
        g.setStroke(INK);
        g.strokeRoundRect(-14, -10, 28, 20, 4, 4);
        g.setFill(INK);
        g.setFont(Font.font("System", 11));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(glyph, 0, 4);
        drawGroundTail(g, 14);
        drawLabel(g, name, INK, 0, 28);
    }
}
