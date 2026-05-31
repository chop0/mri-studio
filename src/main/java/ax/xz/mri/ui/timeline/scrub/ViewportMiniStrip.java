package ax.xz.mri.ui.timeline.scrub;

import ax.xz.mri.ui.time.TimeAxis;
import javafx.scene.layout.StackPane;

/**
 * Thin (18 px) strip that sits <em>above</em> the DAW's main scrub bar
 * and controls what region of the project the editor shows.
 *
 * <p>Mental model: the mini-strip is a "you-are-here" map — its rail spans
 * the full project domain, the rectangle on it is the editor's visible
 * viewport. Drag the rectangle to pan the viewport; drag its edges to
 * zoom; click anywhere on the rail to centre the viewport there.
 *
 * <p><strong>No cursor marker</strong> — scrubbing happens on the DAW
 * main strip below, never here. That keeps the mental model clean: this
 * strip is for "what do I see", the strip below it is for "where am I
 * playing".
 *
 * <p>Built as a {@link ScrubStrip} configured with:
 * <ul>
 *   <li>{@link ScrubStrip.Style#OVERVIEW} — short, condensed</li>
 *   <li>{@link ScrubStrip.InteractionPriority#WINDOW} — clicks pan</li>
 *   <li>domain bound to the full project ({@code [0, maxTime]})</li>
 *   <li>window bound to the editor's viewport (bidirectional)</li>
 *   <li>marker disabled entirely</li>
 * </ul>
 */
public final class ViewportMiniStrip extends StackPane {
    private final ScrubStrip strip = new ScrubStrip();

    public ViewportMiniStrip(TimeAxis timeAxis) {
        getStyleClass().add("viewport-mini-strip");
        getChildren().add(strip);

        strip.style.set(ScrubStrip.Style.OVERVIEW);
        strip.priority.set(ScrubStrip.InteractionPriority.WINDOW);
        strip.windowEditable.set(true);
        strip.markerVisible.set(false);
        strip.markerEditable.set(false);
        strip.showTicks.set(false); // ticks are on the DAW strip below — this is for orientation only
        strip.tickFormatter.set(TimeTickFormatter.INSTANCE);

        strip.domainStart.set(0);
        strip.domainEnd.bind(timeAxis.domain.maxTime);

        strip.windowStart.bindBidirectional(timeAxis.viewport.start);
        strip.windowEnd  .bindBidirectional(timeAxis.viewport.end);

        strip.minWindowSpan.set(1);
        strip.onReset.set(timeAxis.viewport::fit);
        strip.onZoom.set(timeAxis.viewport::zoomAround);
    }

    public ScrubStrip strip() { return strip; }
}
