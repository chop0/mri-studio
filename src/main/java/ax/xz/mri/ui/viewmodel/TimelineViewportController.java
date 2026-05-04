package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.util.MathUtil;

/**
 * Imperative policy layer over {@link ViewportViewModel}. Owns the canonical
 * zoom factor, viewport-bound clamping, and cross-window operations so call
 * sites (toolbar buttons, wheel, pinch, scroll-bar drag) don't reinvent them.
 */
public class TimelineViewportController {
    /** Multiplier applied per zoom step. Matches the editor canvas's existing feel. */
    static final double ZOOM_STEP_IN  = 0.8;
    static final double ZOOM_STEP_OUT = 1.25;

    private final ViewportViewModel viewport;

    public TimelineViewportController(ViewportViewModel viewport) {
        this.viewport = viewport;
    }

    public ViewportViewModel viewport() { return viewport; }

    public void fitViewportToData() {
        viewport.fitViewportToData();
    }

    /**
     * Zoom one canonical step toward (IN) or away from (OUT) {@code timePivot},
     * keeping that point fixed under the cursor. Clamps the resulting viewport
     * to {@code [0, maxTime]}.
     */
    public void zoomViewportAt(double timePivot, ZoomDirection direction) {
        double factor = direction == ZoomDirection.IN ? ZOOM_STEP_IN : ZOOM_STEP_OUT;
        zoomViewportAround(timePivot, factor);
    }

    /** Continuous-factor entry point — trackpad pinch, key-repeat. For one-shot events prefer {@link #zoomViewportAt}. */
    public void zoomViewportAround(double timePivot, double factor) {
        viewport.zoomViewportAround(timePivot, factor);
    }

    public void zoomAnalysisWindowAround(double centreTime, double factor) {
        viewport.zoomAnalysisWindowAround(centreTime, factor);
    }

    /** Pan by {@code deltaTime} microseconds, clamped so the view never crosses {@code [0, maxTime]}. */
    public void panViewportBy(double deltaTime) {
        double max = Math.max(viewport.maxTime.get(), 1);
        double span = viewport.vE.get() - viewport.vS.get();
        double newStart = MathUtil.clamp(viewport.vS.get() + deltaTime, 0, Math.max(0, max - span));
        viewport.setViewport(newStart, newStart + span);
    }

    public void setViewport(double start, double end) {
        viewport.setViewport(start, end);
    }

    public void moveAnalysisStart(double time) {
        viewport.moveAnalysisStart(time);
    }

    public void moveAnalysisEnd(double time) {
        viewport.moveAnalysisEnd(time);
    }

    public void moveAnalysisWindowBy(double deltaTime) {
        viewport.moveAnalysisWindowBy(deltaTime);
    }

    public void setAnalysisWindow(double start, double end) {
        viewport.setAnalysisWindow(start, end);
    }

    public void setCursor(double time) {
        viewport.setCursor(time);
    }

    public void setAnalysisWindowToViewport() {
        viewport.setAnalysisWindow(viewport.vS.get(), viewport.vE.get());
    }

    /** Inverse of {@link #setAnalysisWindowToViewport}: snap the viewport to the analysis window. */
    public void zoomViewportToAnalysisWindow() {
        viewport.setViewport(viewport.tS.get(), viewport.tE.get());
    }

    public void fitAnalysisToData() {
        viewport.fitAnalysisToData();
    }
}
