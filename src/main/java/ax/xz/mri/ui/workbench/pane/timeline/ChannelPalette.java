package ax.xz.mri.ui.workbench.pane.timeline;

import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import ax.xz.mri.util.MathUtil;
import javafx.scene.paint.Color;

/**
 * Single source of truth for channel colours in the DAW editor. Each colour is
 * deterministic from the channel's identity — so two clips on the same
 * channel always share a hue, and different sources remain visually distinct.
 *
 * <p>Colours are chosen purely from the active config's source list; there
 * are no hard-coded special-case channels.
 */
public final class ChannelPalette {
    private ChannelPalette() {}

    private static final Color[] REAL_FAMILY = {
        Color.web("#15803d"), // forest green
        Color.web("#9333ea"), // purple
        Color.web("#be185d"), // magenta
        Color.web("#0f766e"), // teal
        Color.web("#b45309"), // amber-brown
        Color.web("#3f6212"), // olive
        Color.web("#0369a1"), // deep sky
    };

    private static final Color UNKNOWN = Color.web("#5c6571"); // neutral slate
    private static final Color GATE = Color.web("#d97706"); // amber — gate / T/R switch

    /** Colour for a channel, given the active session. */
    public static Color colourFor(SequenceEditSession session, SequenceChannel channel) {
        if (channel == null) return UNKNOWN;
        var src = session.sourceForChannel(channel);
        if (src == null) return UNKNOWN;
        return colourFor(src.kind(), src.name());
    }

    /** Colour for an (AmplitudeKind, fieldName) pair without needing a session. */
    public static Color colourFor(AmplitudeKind kind, String fieldName) {
        if (kind == null) return UNKNOWN;
        return switch (kind) {
            case REAL, STATIC -> pick(REAL_FAMILY, fieldName);
            case GATE -> GATE;
        };
    }

    private static Color pick(Color[] palette, String key) {
        int h = Math.abs((key == null ? 0 : key.hashCode())) % palette.length;
        return palette[h];
    }

    /** A translucent variant of the given colour. */
    public static Color tint(Color base, double alpha) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), MathUtil.clamp01(alpha));
    }
}
