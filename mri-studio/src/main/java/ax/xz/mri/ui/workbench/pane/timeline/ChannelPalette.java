package ax.xz.mri.ui.workbench.pane.timeline;

import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import javafx.scene.paint.Color;

/**
 * Single source of truth for channel colours in the DAW editor. Each colour is
 * deterministic from the channel's identity — so two clips on the same
 * channel always share a hue, different fields remain visually distinct, and
 * QUADRATURE sub-channels (I/Q) share their field's colour.
 *
 * <p>Colours are chosen purely from the active config's field layout; there
 * are no hard-coded special-case channels.
 */
public final class ChannelPalette {
    private ChannelPalette() {}

    private static final Color[] QUADRATURE_FAMILY = {
        Color.web("#1e64c8"), // strong blue
        Color.web("#7c3aed"), // indigo / violet
        Color.web("#0e7490"), // teal
        Color.web("#4338ca"), // cobalt
    };

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

    /** Colour for a channel, given the active session. */
    public static Color colourFor(SequenceEditSession session, SequenceChannel channel) {
        if (channel == null) return UNKNOWN;
        var field = session.fieldForChannel(channel);
        if (field == null) return UNKNOWN;
        return colourFor(field.kind(), field.name());
    }

    /** Colour for an (AmplitudeKind, fieldName) pair without needing a session. */
    public static Color colourFor(AmplitudeKind kind, String fieldName) {
        if (kind == null) return UNKNOWN;
        return switch (kind) {
            case QUADRATURE -> pick(QUADRATURE_FAMILY, fieldName);
            case REAL -> pick(REAL_FAMILY, fieldName);
            case STATIC -> pick(REAL_FAMILY, fieldName); // unreachable in practice (no channels)
        };
    }

    private static Color pick(Color[] palette, String key) {
        int h = Math.abs((key == null ? 0 : key.hashCode())) % palette.length;
        return palette[h];
    }

    /** A translucent variant of the given colour. */
    public static Color tint(Color base, double alpha) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(),
            Math.max(0, Math.min(1, alpha)));
    }
}
