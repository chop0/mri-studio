package ax.xz.mri.ui.timeline.scrub;

import ax.xz.mri.ui.timeline.TimelineMetrics;
import javafx.scene.layout.StackPane;

/**
 * The DAW's <em>main</em> scrub bar — the load-bearing time control of
 * the editor.
 *
 * <p>Sits immediately above the lane stack. Three things happen on this
 * strip:
 * <ol>
 *   <li><strong>Cursor scrubbing.</strong> Click or drag anywhere to
 *       move the playhead. The orange triangle marker tracks the cursor.</li>
 *   <li><strong>Analysis-window selection.</strong> The translucent
 *       blue rectangle is the playback / analysis range that drives
 *       every other pane. Drag its edges to resize, drag its body to
 *       pan, click outside it to set the cursor inside.</li>
 *   <li><strong>Tick labels.</strong> SI-aware time labels sit inside
 *       the strip body, not above it.</li>
 * </ol>
 *
 * <p>Bound to the {@link TimelineMetrics#timeAxis} model:
 * <ul>
 *   <li>domain = the editor's viewport (so ticks track pan/zoom)</li>
 *   <li>window = the analysis window (bidirectional)</li>
 *   <li>marker = the cursor (bidirectional)</li>
 * </ul>
 *
 * <p>Click priority is {@link ScrubStrip.InteractionPriority#MARKER} —
 * a bare click drops the cursor at that point. Edges of the analysis
 * window stay grabbable for resize.
 */
public final class DawScrubStrip extends StackPane {
    private final ScrubStrip strip = new ScrubStrip();

    public DawScrubStrip(TimelineMetrics metrics) {
        getStyleClass().add("daw-scrub-strip");
        getChildren().add(strip);

        var ta = metrics.timeAxis;
        strip.style.set(ScrubStrip.Style.RIBBON);
        strip.priority.set(ScrubStrip.InteractionPriority.MARKER);
        strip.markerEditable.set(true);
        strip.windowEditable.set(true);
        strip.tickFormatter.set(TimeTickFormatter.INSTANCE);

        // Domain follows the viewport so ticks always cover what's on screen.
        strip.domainStart.bind(ta.viewport.start);
        strip.domainEnd  .bind(ta.viewport.end);

        // Window = analysis range. Brackets stay grabbable; if the window's
        // outside the viewport the ScrubStrip clamps the handles to its edge.
        strip.windowStart.bindBidirectional(ta.analysis.start);
        strip.windowEnd  .bindBidirectional(ta.analysis.end);

        // Marker = cursor. Bidirectional so click + drag scrubs.
        strip.marker.bindBidirectional(ta.cursor.time);

        strip.minWindowSpan.set(1);
        // Double-click: zoom-to-fit.
        strip.onReset.set(ta.viewport::fit);
        // Scroll: zoom around the mouse position.
        strip.onZoom.set(ta.viewport::zoomAround);
    }

    public ScrubStrip strip() { return strip; }
}
