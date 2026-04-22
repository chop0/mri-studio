package ax.xz.mri.ui.workbench.pane.timeline;

import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import javafx.scene.paint.Color;

/**
 * Single source of truth for channel colours in the DAW editor. Each colour is
 * deterministic from the channel's identity so that two clips on the same
 * channel always share a hue and different fields remain visually
 * distinguishable.
 *
 * <h3>Rules</h3>
 * <ol>
 *   <li>{@link SequenceChannel#RF_GATE} → warm orange (the only non-field lane).</li>
 *   <li>{@link AmplitudeKind#QUADRATURE} field channels → a blue-family hue,
 *       shifted per field name. Two-letter sub index (I/Q) does not shift the hue —
 *       the field itself owns the colour, the quadrature components share it.</li>
 *   <li>{@link AmplitudeKind#REAL} field channels → a palette rotated by field
 *       name hash — gradients of different families look distinct.</li>
 * </ol>
 */
public final class ChannelPalette {
    private ChannelPalette() {}

    /** RF-gate lane — the only hard-coded colour; it's a sentinel channel. */
    public static final Color GATE = Color.web("#d97706"); // warm amber

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

    /** Colour for a channel, given the active session. */
    public static Color colourFor(SequenceEditSession session, SequenceChannel channel) {
        if (channel == null || channel.isRfGate()) return GATE;
        var field = session.fieldForChannel(channel);
        if (field == null) return REAL_FAMILY[0];
        return colourFor(field.kind(), field.name());
    }

    /** Colour for an (AmplitudeKind, fieldName) pair without needing a session. */
    public static Color colourFor(AmplitudeKind kind, String fieldName) {
        if (kind == null) return GATE;
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

    /** A translucent variant of the given colour, for clip fills. */
    public static Color tint(Color base, double alpha) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(),
            Math.max(0, Math.min(1, alpha)));
    }
}
