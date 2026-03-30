package ax.xz.mri.ui.viewmodel;

/** Imperative controller for timeline gestures over the shared viewport state. */
public class TimelineViewportController {
    private final ViewportViewModel viewport;

    public TimelineViewportController(ViewportViewModel viewport) {
        this.viewport = viewport;
    }

    public void fitViewportToData() {
        viewport.fitViewportToData();
    }

    public void zoomViewportAround(double centreTime, double factor) {
        viewport.zoomViewportAround(centreTime, factor);
    }

    public void panViewportBy(double deltaTime) {
        viewport.panViewportBy(deltaTime);
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
}
