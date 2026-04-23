package ax.xz.mri.ui.theme;

import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/** Light-theme colour constants and font definitions for canvas rendering. */
public final class StudioTheme {
    private StudioTheme() {}

    // ── Canvas background colours ─────────────────────────────────────────────
    public static final Color BG  = Color.web("#ffffff");
    public static final Color BG2 = Color.web("#f5f5f5");
    public static final Color GR  = Color.web("#d0d0d0");
    public static final Color TX  = Color.web("#1a1a1a");
    public static final Color TX2 = Color.web("#707070");
    public static final Color AC  = Color.web("#0078d4");
    public static final Color CUR = Color.web("#e06000");

    /** Palette for isochromats; cycles when more than 10 are defined. */
    public static final Color[] ISOCHROMAT_COLOURS = {
        Color.web("#d32f2f"), Color.web("#e07000"), Color.web("#2e7d32"),
        Color.web("#1565c0"), Color.web("#7b1fa2"), Color.web("#c2185b"),
        Color.web("#00897b"), Color.web("#ef6c00"), Color.web("#4527a0"),
        Color.web("#558b2f"),
    };

    // ── Fonts (system default) ────────────────────────────────────────────────
    private static final String FONT_FAMILY = Font.getDefault().getFamily();

    public static final Font UI_7       = Font.font(FONT_FAMILY, 7);
    public static final Font UI_8       = Font.font(FONT_FAMILY, 8);
    public static final Font UI_9       = Font.font(FONT_FAMILY, 9);
    public static final Font UI_10      = Font.font(FONT_FAMILY, 10);
    public static final Font UI_BOLD_7  = Font.font(FONT_FAMILY, FontWeight.BOLD, 7);
    public static final Font UI_BOLD_9  = Font.font(FONT_FAMILY, FontWeight.BOLD, 9);
    public static final Font UI_BOLD_10 = Font.font(FONT_FAMILY, FontWeight.BOLD, 10);

    // Aliases so existing canvas code compiles without mass-rename
    public static final Font MONO_7       = UI_7;
    public static final Font MONO_8       = UI_8;
    public static final Font MONO_10      = UI_10;
    public static final Font MONO_BOLD_7  = UI_BOLD_7;
    public static final Font MONO_BOLD_9  = UI_BOLD_9;
    public static final Font MONO_BOLD_10 = UI_BOLD_10;

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Return a colour with the given opacity applied. */
    public static Color withAlpha(Color c, double alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }
}
