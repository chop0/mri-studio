package ax.xz.mri.ui.theme;

import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/** Dark-theme colour constants and font definitions. Mirrors constants.ts. */
public final class StudioTheme {
    private StudioTheme() {}

    // ── Colours ───────────────────────────────────────────────────────────────
    public static final Color BG  = Color.web("#0b0b12");
    public static final Color BG2 = Color.web("#101018");
    public static final Color GR  = Color.web("#1a1a28");
    public static final Color TX  = Color.web("#94a3b8");
    public static final Color TX2 = Color.web("#4a5568");
    public static final Color AC  = Color.web("#3b82f6");
    public static final Color CUR = Color.web("#f59e0b");

    /** Palette for isochromats; cycles when more than 10 are defined. */
    public static final Color[] ISOCHROMAT_COLOURS = {
        Color.web("#ef4444"), Color.web("#f59e0b"), Color.web("#22c55e"),
        Color.web("#3b82f6"), Color.web("#a855f7"), Color.web("#ec4899"),
        Color.web("#14b8a6"), Color.web("#f97316"), Color.web("#6366f1"),
        Color.web("#84cc16"),
    };

    // ── Fonts ─────────────────────────────────────────────────────────────────
    public static final Font MONO_7       = Font.font("monospace", 7);
    public static final Font MONO_8       = Font.font("monospace", 8);
    public static final Font MONO_10      = Font.font("monospace", 10);
    public static final Font MONO_BOLD_7  = Font.font("monospace", FontWeight.BOLD, 7);
    public static final Font MONO_BOLD_9  = Font.font("monospace", FontWeight.BOLD, 9);
    public static final Font MONO_BOLD_10 = Font.font("monospace", FontWeight.BOLD, 10);

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Return a colour with the given opacity applied. */
    public static Color withAlpha(Color c, double alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }
}
