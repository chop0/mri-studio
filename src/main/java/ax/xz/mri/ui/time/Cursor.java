package ax.xz.mri.ui.time;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;

/**
 * The playhead. Lives in {@code [0, domain.maxTime()]} — independent of the
 * viewport and the analysis window, both of which can move freely without
 * dragging the cursor along. Scrubbing past either of those is allowed; UIs
 * that care will dim themselves accordingly.
 */
public final class Cursor {
    public final DoubleProperty time = new SimpleDoubleProperty(0);

    private final Domain domain;
    private boolean clamping;

    public Cursor(Domain domain) {
        this.domain = domain;
        time.addListener((o, was, now) -> applyClamp());
        domain.maxTime.addListener((o, was, now) -> applyClamp());
    }

    public void scrubTo(double t) {
        time.set(domain.clamp(t));
    }

    private void applyClamp() {
        if (clamping) return;
        double clamped = domain.clamp(time.get());
        if (clamped != time.get()) {
            clamping = true;
            try {
                time.set(clamped);
            } finally {
                clamping = false;
            }
        }
    }
}
