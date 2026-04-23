package ax.xz.mri.ui.canvas;

import javafx.scene.paint.Color;

/**
 * Colour utilities for heatmap rendering.
 * Port of {@code hue2rgb()} and {@code hue2rgbBytes()} from canvas.ts.
 */
public final class ColourUtil {
    private ColourUtil() {}

    /**
     * Convert a phase angle (hue) and transverse magnetisation (brightness) to a Color.
     * Hue wheel: red=0°, green=120°, blue=240°, wrapping at 360°.
     */
    public static Color hue2color(double phaseDeg, double brightness) {
        double hh = (((phaseDeg % 360) + 360) % 360) / 60.0;
        int    hi = (int) hh;
        double f  = hh - hi;
        double q  = 1 - f;
        double r, g, b;
        switch (hi % 6) {
            case 0 -> { r = 1; g = f; b = 0; }
            case 1 -> { r = q; g = 1; b = 0; }
            case 2 -> { r = 0; g = 1; b = f; }
            case 3 -> { r = 0; g = q; b = 1; }
            case 4 -> { r = f; g = 0; b = 1; }
            default-> { r = 1; g = 0; b = q; }
        }
        return Color.color(
            clamp(r * brightness), clamp(g * brightness), clamp(b * brightness)
        );
    }

    /** Neutral ramp for brightness-only shading when phase hue is disabled. */
    public static Color monochrome(double brightness) {
        return Color.hsb(210, 0.12, clamp(0.28 + 0.52 * clamp(brightness)));
    }

    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }
}
