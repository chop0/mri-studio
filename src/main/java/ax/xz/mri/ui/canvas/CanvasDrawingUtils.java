package ax.xz.mri.ui.canvas;

import ax.xz.mri.ui.theme.StudioTheme;
import ax.xz.mri.util.MathUtil;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/** Shared canvas-rendering primitives used by multiple analysis panes. */
public final class CanvasDrawingUtils {
    private CanvasDrawingUtils() {}

    /**
     * Paint a small rounded label-pill centred horizontally on {@code centerX},
     * accent-tinted with a subtle stroke and text in the studio's neutral
     * dark colour. Width auto-sizes to the text with a configurable minimum,
     * and the pill is clamped to {@code [xMin, xMax]} so it never overflows
     * the plot area.
     */
    public static void drawBadge(
        GraphicsContext g, double centerX, double y, String text, Color accent,
        Font font, double minWidth, double xMin, double xMax
    ) {
        g.setFont(font);
        double width = Math.max(minWidth, text.length() * 4.9);
        double x = MathUtil.clamp(centerX - width / 2, xMin, xMax - width);
        g.setFill(Color.color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0.10));
        g.fillRoundRect(x, y, width, 12, 6, 6);
        g.setStroke(Color.color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0.45));
        g.setLineWidth(0.6);
        g.strokeRoundRect(x, y, width, 12, 6, 6);
        g.setFill(Color.color(0.18, 0.2, 0.24, 0.9));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(text, x + width / 2, y + 8.2);
        g.setTextAlign(TextAlignment.LEFT);
    }

    /** Default-font convenience overload using {@link StudioTheme#UI_7}. */
    public static void drawBadge(
        GraphicsContext g, double centerX, double y, String text, Color accent,
        double minWidth, double xMin, double xMax
    ) {
        drawBadge(g, centerX, y, text, accent, StudioTheme.UI_7, minWidth, xMin, xMax);
    }
}
