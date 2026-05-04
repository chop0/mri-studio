package ax.xz.mri.ui.viewmodel;

/**
 * Direction passed to {@link TimelineViewportController#zoomViewportAt} and
 * friends. The controller owns the canonical step factor so callers don't
 * scatter zoom-strength constants across the codebase.
 */
public enum ZoomDirection {
    IN, OUT;

    /** {@code true} if a positive scroll-wheel deltaY should map to this direction (i.e. it's IN). */
    public static ZoomDirection ofWheelDelta(double deltaY) {
        return deltaY > 0 ? IN : OUT;
    }
}
