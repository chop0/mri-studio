package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.util.MathUtil;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;

/** Shared analysis window, timeline viewport, and cursor state with hard invariants. */
public class ViewportViewModel {
    private static final double MIN_VIEWPORT_SPAN = 1.0;
    private static final double MIN_ANALYSIS_SPAN = 1.0;

    public final DoubleProperty tS = new SimpleDoubleProperty(0);
    public final DoubleProperty tE = new SimpleDoubleProperty(1000);
    public final DoubleProperty vS = new SimpleDoubleProperty(0);
    public final DoubleProperty vE = new SimpleDoubleProperty(1000);
    public final DoubleProperty tC = new SimpleDoubleProperty(0);
    public final DoubleProperty maxTime = new SimpleDoubleProperty(1000);

    private boolean normalizing;
    private boolean suppressMaxTimeReset;

    public ViewportViewModel() {
        var normalizer = (javafx.beans.value.ChangeListener<Number>) (obs, oldValue, newValue) -> normalize();
        tS.addListener(normalizer);
        tE.addListener(normalizer);
        vS.addListener(normalizer);
        vE.addListener(normalizer);
        tC.addListener(normalizer);
        maxTime.addListener((obs, oldValue, newValue) -> {
            if (!suppressMaxTimeReset) resetToFullRange();
            else normalize(); // still enforce invariants, just don't reset position
        });
        normalize();
    }

    /**
     * Update maxTime without resetting the viewport to the full range.
     * Used when re-simulation changes the time bounds but the user's
     * zoom/pan position should be preserved.
     */
    public void setMaxTimePreservePosition(double newMax) {
        suppressMaxTimeReset = true;
        try {
            maxTime.set(newMax);
        } finally {
            suppressMaxTimeReset = false;
        }
    }

    public void resetToFullRange() {
        double max = Math.max(maxTime.get(), MIN_VIEWPORT_SPAN);
        vS.set(0);
        vE.set(max);
        tS.set(0);
        tE.set(max);
        tC.set(max / 2.0);
        normalize();
    }

    public void fitViewportToData() {
        vS.set(0);
        vE.set(Math.max(maxTime.get(), MIN_VIEWPORT_SPAN));
        normalize();
    }

    public void zoomViewportAround(double centreTime, double factor) {
        double currentSpan = Math.max(MIN_VIEWPORT_SPAN, vE.get() - vS.get());
        double maxSpan = Math.max(maxTime.get(), MIN_VIEWPORT_SPAN);
        double nextSpan = MathUtil.clamp(currentSpan * factor, MIN_VIEWPORT_SPAN, maxSpan);
        double nextStart = centreTime - (centreTime - vS.get()) / currentSpan * nextSpan;
        vS.set(nextStart);
        vE.set(nextStart + nextSpan);
        normalize();
    }

    public void zoomAnalysisWindowAround(double centreTime, double factor) {
        double currentSpan = Math.max(MIN_ANALYSIS_SPAN, tE.get() - tS.get());
        double nextSpan = MathUtil.clamp(currentSpan * factor, MIN_ANALYSIS_SPAN, Math.max(maxTime.get(), MIN_ANALYSIS_SPAN));
        double nextStart = centreTime - (centreTime - tS.get()) / currentSpan * nextSpan;
        tS.set(nextStart);
        tE.set(nextStart + nextSpan);
        normalize();
    }

    public void panViewportBy(double deltaTime) {
        vS.set(vS.get() + deltaTime);
        vE.set(vE.get() + deltaTime);
        normalize();
    }

    public void setViewport(double start, double end) {
        vS.set(start);
        vE.set(end);
        normalize();
    }

    public void setAnalysisWindow(double start, double end) {
        tS.set(start);
        tE.set(end);
        normalize();
    }

    public void moveAnalysisStart(double start) {
        tS.set(start);
        normalize();
    }

    public void moveAnalysisEnd(double end) {
        tE.set(end);
        normalize();
    }

    public void moveAnalysisWindowBy(double deltaTime) {
        tS.set(tS.get() + deltaTime);
        tE.set(tE.get() + deltaTime);
        normalize();
    }

    public void setCursor(double time) {
        tC.set(time);
        normalize();
    }

    public void fitAnalysisToData() {
        double max = Math.max(maxTime.get(), MIN_ANALYSIS_SPAN);
        tS.set(0);
        tE.set(max);
        normalize();
    }

    /** Capture the current viewport state for later restoration. */
    public ViewportSnapshot captureSnapshot() {
        return new ViewportSnapshot(vS.get(), vE.get(), tS.get(), tE.get(), tC.get(), maxTime.get());
    }

    /** Restore a previously captured snapshot without triggering a full-range reset. */
    public void restoreSnapshot(ViewportSnapshot snap) {
        suppressMaxTimeReset = true;
        normalizing = true;
        try {
            maxTime.set(snap.maxTime());
            vS.set(snap.vS());
            vE.set(snap.vE());
            tS.set(snap.tS());
            tE.set(snap.tE());
            tC.set(snap.tC());
        } finally {
            normalizing = false;
            suppressMaxTimeReset = false;
        }
        normalize();
    }

    public record ViewportSnapshot(double vS, double vE, double tS, double tE, double tC, double maxTime) {}

    private void normalize() {
        if (normalizing) return;
        normalizing = true;
        try {
            double max = Math.max(maxTime.get(), MIN_VIEWPORT_SPAN);

            // Analysis window and viewport are independent — both clamped to
            // [0, max] but neither dragged by the other. Cursor lives inside
            // the analysis window since that's where measurements are read.
            double analysisSpan = MathUtil.clamp(tE.get() - tS.get(), MIN_ANALYSIS_SPAN, max);
            double analysisStart = MathUtil.clamp(tS.get(), 0, max - analysisSpan);
            double analysisEnd = MathUtil.clamp(tE.get(), analysisStart + analysisSpan, max);
            if (analysisEnd - analysisStart < MIN_ANALYSIS_SPAN) {
                analysisEnd = Math.min(max, analysisStart + MIN_ANALYSIS_SPAN);
                analysisStart = Math.max(0, analysisEnd - MIN_ANALYSIS_SPAN);
            }

            double viewSpan = MathUtil.clamp(vE.get() - vS.get(), MIN_VIEWPORT_SPAN, max);
            double viewStart = MathUtil.clamp(vS.get(), 0, Math.max(0, max - viewSpan));
            double viewEnd = viewStart + viewSpan;

            double cursor = MathUtil.clamp(tC.get(), analysisStart, analysisEnd);

            vS.set(viewStart);
            vE.set(viewEnd);
            tS.set(analysisStart);
            tE.set(analysisEnd);
            tC.set(cursor);
        } finally {
            normalizing = false;
        }
    }
}
