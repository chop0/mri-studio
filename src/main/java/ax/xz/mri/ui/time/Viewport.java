package ax.xz.mri.ui.time;

import ax.xz.mri.util.MathUtil;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;

/**
 * The visible time range. Self-clamps to {@code [0, domain.maxTime()]} with a
 * minimum span of {@link #MIN_SPAN}. Mutations through {@code start}/{@code end}
 * directly are honoured and re-normalised; structural mutations should use the
 * named methods.
 */
public final class Viewport {
    public static final double MIN_SPAN = 1.0;

    public final DoubleProperty start = new SimpleDoubleProperty(0);
    public final DoubleProperty end = new SimpleDoubleProperty(1000);

    private final Domain domain;
    private boolean normalizing;

    public Viewport(Domain domain) {
        this.domain = domain;
        end.set(domain.maxTime());
        start.addListener((o, was, now) -> normalize());
        end.addListener((o, was, now) -> normalize());
        domain.maxTime.addListener((o, was, now) -> normalize());
    }

    public double span() {
        return end.get() - start.get();
    }

    public void setSpan(double s, double e) {
        start.set(s);
        end.set(e);
    }

    public void zoomAround(double anchor, double factor) {
        double currentSpan = Math.max(MIN_SPAN, span());
        double max = domain.maxTime();
        double nextSpan = MathUtil.clamp(currentSpan * factor, MIN_SPAN, max);
        double nextStart = anchor - (anchor - start.get()) / currentSpan * nextSpan;
        setSpan(nextStart, nextStart + nextSpan);
    }

    public void panBy(double deltaTime) {
        setSpan(start.get() + deltaTime, end.get() + deltaTime);
    }

    public void fit() {
        setSpan(0, domain.maxTime());
    }

    private void normalize() {
        if (normalizing) return;
        normalizing = true;
        try {
            double max = domain.maxTime();
            double rawSpan = end.get() - start.get();
            double clampedSpan = MathUtil.clamp(rawSpan, MIN_SPAN, max);
            double clampedStart = MathUtil.clamp(start.get(), 0, Math.max(0, max - clampedSpan));
            start.set(clampedStart);
            end.set(clampedStart + clampedSpan);
        } finally {
            normalizing = false;
        }
    }
}
