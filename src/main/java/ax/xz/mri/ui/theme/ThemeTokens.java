package ax.xz.mri.ui.theme;

import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Structured design tokens consumed by canvas-rendering code that can't reach
 * CSS variables. Mirrors the {@code -studio-*} variables in {@code studio.css}
 * so a colour change made in one place is reflected in the other.
 *
 * <p>Sub-classes are organised by concern: {@link Pad} for spacing,
 * {@link Radius} for corner rounding, {@link Elev} for shadow/elevation,
 * {@link Stroke} for line widths, {@link Fonts} for typography, and
 * {@link Tone} for the surface/border/text palette. Each is a holder of
 * {@code public static final} constants; nothing here is instantiated.
 *
 * <p>For trace colours specifically (simulator vs. hardware lines + fills) see
 * {@link TraceColours} — those vary with run context and live in their own
 * file to avoid mixing physical-meaning colours with neutral chrome.
 */
public final class ThemeTokens {
    private ThemeTokens() {}

    /** Spacing scale — multiples of 2px, matching the dense desktop language. */
    public static final class Pad {
        private Pad() {}
        public static final double XXS = 1;
        public static final double XS  = 2;
        public static final double SM  = 4;
        public static final double MD  = 6;
        public static final double LG  = 8;
        public static final double XL  = 12;
        public static final double XXL = 16;
    }

    /** Corner radius scale — chips, clip pills, popovers. */
    public static final class Radius {
        private Radius() {}
        public static final double NONE  = 0;
        public static final double XS    = 1;
        public static final double SM    = 2;
        public static final double MD    = 4;
        public static final double LG    = 6;
        public static final double PILL  = 999;
    }

    /** Shadow / elevation tints — applied as {@code Color} with low alpha. */
    public static final class Elev {
        private Elev() {}
        public static final Color SHADOW_LO = Color.web("#000000", 0.04);
        public static final Color SHADOW_MD = Color.web("#000000", 0.10);
        public static final Color SHADOW_HI = Color.web("#000000", 0.18);
    }

    /** Line-width scale — clip outlines, axes, snap guides. */
    public static final class Stroke {
        private Stroke() {}
        public static final double HAIRLINE = 0.5;
        public static final double THIN     = 1.0;
        public static final double MED      = 1.5;
        public static final double THICK    = 2.0;
        public static final double BOLD     = 3.0;
    }

    /** Pre-resolved fonts at the sizes used across canvas-rendered chrome. */
    public static final class Fonts {
        private Fonts() {}
        private static final String FAMILY = Font.getDefault().getFamily();
        public static final Font TINY      = Font.font(FAMILY, 7);
        public static final Font SMALL     = Font.font(FAMILY, 9);
        public static final Font BODY      = Font.font(FAMILY, 11);
        public static final Font LABEL     = Font.font(FAMILY, FontWeight.BOLD, 10);
        public static final Font HEADING   = Font.font(FAMILY, FontWeight.BOLD, 12);
    }

    /** Surface / border / text palette — mirrors {@code -studio-*} CSS vars. */
    public static final class Tone {
        private Tone() {}
        public static final Color BG               = Color.web("#eff1f4");
        public static final Color BG_SUBTLE        = Color.web("#e5e8ec");
        public static final Color SURFACE          = Color.web("#ffffff");
        public static final Color SURFACE_MUTED    = Color.web("#f4f5f7");
        public static final Color SURFACE_HOVER    = Color.web("#e7edf5");

        public static final Color BORDER           = Color.web("#c5cad1");
        public static final Color BORDER_SUBTLE    = Color.web("#dde0e5");
        public static final Color BORDER_STRONG    = Color.web("#9aa2ad");
        public static final Color BORDER_FOCUS     = Color.web("#0b5cad");

        public static final Color TEXT             = Color.web("#1b1f24");
        public static final Color TEXT_SECONDARY   = Color.web("#3c434e");
        public static final Color TEXT_TERTIARY    = Color.web("#5c6571");
        public static final Color TEXT_MUTED       = Color.web("#8a919b");
        public static final Color TEXT_INVERSE     = Color.web("#ffffff");

        public static final Color ACCENT           = Color.web("#0b5cad");
        public static final Color ACCENT_HOVER     = Color.web("#094f94");
        public static final Color ACCENT_ACTIVE    = Color.web("#063d73");
        public static final Color ACCENT_SUBTLE    = Color.web("#0b5cad", 0.10);
        public static final Color ACCENT_TINT      = Color.web("#0b5cad", 0.18);

        public static final Color SUCCESS          = Color.web("#147a3f");
        public static final Color WARNING          = Color.web("#a45a00");
        public static final Color DANGER           = Color.web("#b42318");
        public static final Color DANGER_SUBTLE    = Color.web("#b42318", 0.10);

        /** Cursor scrub colour — orange to stand out against the cool blue accent. */
        public static final Color CURSOR           = Color.web("#e06000");
    }
}
