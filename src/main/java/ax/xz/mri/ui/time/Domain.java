package ax.xz.mri.ui.time;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;

/**
 * The time domain: {@code [0, maxTime]} in microseconds. The single source of
 * bounds every other time-axis sub-object clamps against. Mutating {@code maxTime}
 * never resets the cursor, the viewport, or the analysis window — they each
 * react by clamping themselves and only as far as needed to respect the new
 * bound. This is deliberately different from the legacy ViewportViewModel,
 * which snapped everything to the full range on any maxTime change.
 */
public final class Domain {
    public static final double MIN_TIME = 1.0;

    public final DoubleProperty maxTime = new SimpleDoubleProperty(1000);

    public double maxTime() {
        return Math.max(MIN_TIME, maxTime.get());
    }

    public double clamp(double t) {
        return Math.max(0, Math.min(t, maxTime()));
    }
}
