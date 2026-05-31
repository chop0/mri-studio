package ax.xz.mri.ui.timeline.scrub;

/**
 * Tick formatter that picks a single SI unit (μs / ms / s) per axis range.
 *
 * <p>"Single unit per axis" matters because mixing prefixes mid-axis ("500 μs
 * … 1.00 ms") reads as visually inconsistent — every label should share the
 * same unit so the eye can compare them at a glance. The pick is based on the
 * axis's visible span, not on individual tick values.
 */
public enum TimeTickFormatter implements ScrubStrip.TickFormatter {
    INSTANCE;

    @Override
    public String format(double micros, double visibleSpan) {
        if (visibleSpan >= 1_000_000) return String.format("%.2f s",  micros * 1e-6);
        if (visibleSpan >= 1_000)     return String.format("%.2f ms", micros * 1e-3);
        return String.format("%.0f μs", micros);
    }
}
