package ax.xz.mri.ui.timeline;

import ax.xz.mri.model.sequence.Track;
import ax.xz.mri.ui.edit.EditSession;
import ax.xz.mri.ui.time.TimeAxis;
import ax.xz.mri.ui.timeline.element.cursor.CursorOverlay;
import ax.xz.mri.ui.timeline.element.outputband.OutputBand;
import ax.xz.mri.ui.timeline.element.snapchip.SnapChip;
import ax.xz.mri.ui.timeline.element.track.TrackHeader;
import ax.xz.mri.ui.timeline.element.track.TrackLane;
import ax.xz.mri.ui.timeline.menu.MenuChain;
import ax.xz.mri.ui.timeline.scrub.DawScrubStrip;
import ax.xz.mri.ui.timeline.scrub.ViewportMiniStrip;
import javafx.beans.value.ObservableDoubleValue;
import javafx.collections.ListChangeListener;
import javafx.geometry.Orientation;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.control.ScrollPane;

import java.util.HashMap;
import java.util.Map;

/**
 * Top-level scene-graph composition for the DAW timeline.
 *
 * <p>Layout (top → bottom):
 * <pre>
 *   ┌──────── ViewportMiniStrip ──────────────────────────────────────┐  18 px
 *   │   full-domain rail; viewport rect drags to set what the editor │
 *   │   shows. No cursor marker — scrubbing happens below.           │
 *   ├──────── DawScrubStrip ──────────────────────────────────────────┤  36 px
 *   │   viewport-domain rail; analysis-window rect drives playback;  │
 *   │   orange cursor handle scrubs the playhead. Tick labels.       │
 *   ├──────── SplitPane ──────────────────────────────────────────────┤
 *   │ ┌── headers ──┐ ┌── lane stack ───────────────────────────────┐ │
 *   │ │ TrackHeader │ │ TrackLane (with absolute-positioned Clips)  │ │
 *   │ │ TrackHeader │ │ TrackLane                                   │ │
 *   │ │  …          │ │  …                                          │ │
 *   │ └─────────────┘ └─────────────────────────────────────────────┘ │
 *   │ ── divider ──                                                   │
 *   │ OutputBand (read-only probe rows)                               │
 *   └─────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>The lane stack and the time-axis ribbon share a single
 * {@link TimelineMetrics} — every clip and overlay derives its X position
 * from {@link TimelineMetrics#pxPerMicro}. That binding is the load-bearing
 * piece of the rebuild: pan/zoom (from any source) re-evaluates it once and
 * every node re-lays out automatically. There is no manual repaint cascade.
 *
 * <p>Mouse wheel scrolls the viewport; {@code Cmd}/{@code Ctrl}+wheel zooms
 * around the cursor's X. JavaFX's {@code setOnScroll} and {@code setOnZoom}
 * deliver these as first-class events — no platform-specific glue.
 */
public final class TimelineRoot extends BorderPane {
    private final EditSession session;
    private final TimeAxis timeAxis;
    private final TimelineMetrics metrics;

    private final ViewportMiniStrip viewportMiniStrip;
    private final DawScrubStrip dawScrubStrip;
    private final VBox headerColumn = new VBox();
    private final VBox laneStack = new VBox();
    private final StackPane laneOverlay = new StackPane();
    private final SnapChip snapChip;
    private final CursorOverlay cursorOverlay;
    private final ScrollPane laneScroll;
    private final OutputBand outputBand;
    private final SplitPane verticalSplit;
    private final Map<String, TrackLane> lanesByTrackId = new HashMap<>();
    private final Map<String, TrackHeader> headersByTrackId = new HashMap<>();

    private java.util.function.Supplier<ax.xz.mri.model.sequence.ClipKind> activeCreationKind = () -> null;

    public TimelineRoot(EditSession session, TimeAxis timeAxis) {
        this.session = session;
        this.timeAxis = timeAxis;
        getStyleClass().add("timeline-root");

        var laneStackHost = new StackPane();
        laneStackHost.getStyleClass().add("lane-stack-host");
        laneStack.getStyleClass().add("lane-stack");
        laneOverlay.getStyleClass().add("lane-overlay");
        laneOverlay.setMouseTransparent(true);
        laneOverlay.setPickOnBounds(false);
        laneStackHost.getChildren().addAll(laneStack, laneOverlay);

        // The lane width drives pxPerMicro. Bind it to the lane stack's actual
        // width minus a tiny inset so the rightmost clip-edge isn't clipped.
        ObservableDoubleValue laneWidth = laneStackHost.widthProperty();
        this.metrics = new TimelineMetrics(timeAxis, laneWidth);

        this.viewportMiniStrip = new ViewportMiniStrip(timeAxis);
        this.dawScrubStrip = new DawScrubStrip(metrics);
        this.snapChip = new SnapChip(session, metrics);
        this.cursorOverlay = new CursorOverlay(session, metrics);
        this.outputBand = new OutputBand(session, metrics);

        laneOverlay.getChildren().addAll(cursorOverlay, snapChip);
        cursorOverlay.prefHeightProperty().bind(laneOverlay.heightProperty());
        cursorOverlay.prefWidthProperty().bind(laneOverlay.widthProperty());
        snapChip.prefHeightProperty().bind(laneOverlay.heightProperty());
        snapChip.prefWidthProperty().bind(laneOverlay.widthProperty());

        headerColumn.getStyleClass().add("track-header-column");
        // Hard-lock the header column width — letting it negotiate with its
        // children produced jitter (e.g. the ComboBox briefly settling on a
        // narrower preferred width during initial layout). Locking keeps the
        // gutter spacer above the time-axis ribbon perfectly aligned with the
        // lane area's left edge.
        headerColumn.setMinWidth(180);
        headerColumn.setPrefWidth(180);
        headerColumn.setMaxWidth(180);

        var laneRow = new HBox();
        laneRow.getStyleClass().add("track-lane-row");
        laneRow.getChildren().addAll(headerColumn, laneStackHost);
        HBox.setHgrow(laneStackHost, Priority.ALWAYS);

        // Lane scroll lets us scroll vertically when there are more tracks than
        // fit; horizontal scrolling is done via the viewport zoom/pan model.
        laneScroll = new ScrollPane(laneRow);
        laneScroll.getStyleClass().add("lane-scroll");
        laneScroll.setFitToWidth(true);
        // fitToHeight=true lets a few tall tracks share extra vertical space;
        // each lane has a minHeight floor so the routing rows stay legible
        // when the editor pane gets squeezed by the analysis pane below.
        laneScroll.setFitToHeight(true);
        laneScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        verticalSplit = new SplitPane();
        verticalSplit.setOrientation(Orientation.VERTICAL);
        verticalSplit.getItems().addAll(laneScroll, outputBand);
        verticalSplit.setDividerPositions(0.62);
        // The output band is sized to its content — when there are no enabled
        // probes its preferred height is zero, so it collapses out of view
        // instead of leaving a giant empty grey strip below the lanes. The
        // SplitPane resizes only the lane area when the window resizes; the
        // band stays at the natural height of its rows.
        SplitPane.setResizableWithParent(outputBand, false);
        outputBand.minHeightProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
            () -> outputBand.getChildren().isEmpty() ? 0.0 : 28.0, outputBand.getChildren()));
        outputBand.prefHeightProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
            () -> outputBand.getChildren().isEmpty() ? 0.0 : outputBand.getChildren().size() * 36.0,
            outputBand.getChildren()));
        outputBand.maxHeightProperty().bind(outputBand.prefHeightProperty());

        setTop(buildTopStrips());
        setCenter(verticalSplit);

        rebuildTrackRows();
        session.tracks.addListener((ListChangeListener<Track>) c -> rebuildTrackRows());

        wireScrollAndZoom(laneStackHost);
        wireContextMenuChain();
    }

    /**
     * Single context-menu handler at the timeline root — uses {@link MenuChain}
     * to walk the press target's ancestor chain, collecting items from every
     * {@link ax.xz.mri.ui.timeline.menu.TimelineContextMenuContributor} along
     * the way. Per-element wiring isn't needed; just implementing the
     * interface on a Node makes its menu items show up.
     */
    private void wireContextMenuChain() {
        addEventHandler(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, e -> {
            var pick = e.getPickResult();
            var target = pick == null ? null : pick.getIntersectedNode();
            if (target == null) target = (javafx.scene.Node) e.getTarget();
            var menu = MenuChain.buildFor(target);
            if (menu == null) return;
            ax.xz.mri.ui.menu.ActiveContextMenu.show(menu, this, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    public TimelineMetrics metrics() { return metrics; }
    public OutputBand outputBand() { return outputBand; }
    public DawScrubStrip dawScrubStrip() { return dawScrubStrip; }
    public ViewportMiniStrip viewportMiniStrip() { return viewportMiniStrip; }
    /** Diagnostic accessor — used by the UI preview to inspect actual layout widths. */
    public javafx.scene.Node laneStackHost() {
        // The lane host is the second child of the centre split pane's first item (laneRow > laneStackHost).
        return laneStack.getParent();
    }

    /**
     * Plug in the toolbar's active creation kind. When non-null, dragging on
     * an empty lane creates a clip of that kind. The supplier is checked on
     * every press so changing the toolbar takes effect immediately without
     * re-binding every lane.
     */
    public void setActiveCreationKind(java.util.function.Supplier<ax.xz.mri.model.sequence.ClipKind> supplier) {
        this.activeCreationKind = supplier == null ? () -> null : supplier;
        for (var lane : lanesByTrackId.values()) lane.setActiveCreationKind(this.activeCreationKind);
    }

    // ── Track rows ───────────────────────────────────────────────────────────

    private void rebuildTrackRows() {
        var live = new HashMap<String, Track>();
        for (var t : session.tracks) live.put(t.id(), t);

        // Drop removed tracks first.
        lanesByTrackId.keySet().removeIf(id -> {
            if (live.containsKey(id)) return false;
            laneStack.getChildren().remove(lanesByTrackId.get(id));
            return true;
        });
        headersByTrackId.keySet().removeIf(id -> {
            if (live.containsKey(id)) return false;
            headerColumn.getChildren().remove(headersByTrackId.get(id));
            return true;
        });

        // Each lane is sized to fit its TrackHeader's content row (chevron +
        // name + chip pair = ~52 px). No Vgrow on the lane — empty space
        // below the lane stack is reclaimed by the SplitPane's lower half
        // (the OutputBand) or, if no probes are enabled, simply absent.
        // Previous version stretched lanes up to 200 px which left a giant
        // white expanse below the routing combos in the gutter.
        laneStack.getChildren().clear();
        headerColumn.getChildren().clear();
        int idx = 0;
        for (var track : session.tracks) {
            var lane = lanesByTrackId.computeIfAbsent(track.id(),
                id -> {
                    var l = new TrackLane(session, metrics, track);
                    l.setActiveCreationKind(activeCreationKind);
                    return l;
                });
            lane.trackProperty().set(track);
            String tid = track.id();
            javafx.beans.binding.DoubleBinding height = javafx.beans.binding.Bindings.createDoubleBinding(
                () -> session.isTrackCollapsed(tid) ? 22.0 : 70.0,
                session.collapsedTrackIds);
            lane.minHeightProperty().bind(height);
            lane.prefHeightProperty().bind(height);
            lane.maxHeightProperty().bind(height);
            VBox.setVgrow(lane, Priority.NEVER);
            // Zebra stripe on every other lane.
            lane.getStyleClass().remove("track-lane-stripe");
            if (idx % 2 == 1) lane.getStyleClass().add("track-lane-stripe");
            laneStack.getChildren().add(lane);

            var header = headersByTrackId.computeIfAbsent(track.id(),
                id -> new TrackHeader(session, track));
            header.trackProperty().set(track);
            header.minHeightProperty().bind(lane.heightProperty());
            header.prefHeightProperty().bind(lane.heightProperty());
            header.maxHeightProperty().bind(lane.heightProperty());
            VBox.setVgrow(header, Priority.NEVER);
            headerColumn.getChildren().add(header);
            idx++;
        }
    }

    // ── Wheel zoom + Shift-wheel pan + pinch zoom ──────────────────────────
    //
    // Filters live on the TimelineRoot itself (capture phase) so they beat
    // the inner ScrollPane's default scroll handling, which would otherwise
    // consume the event for vertical scrolling. The previous wiring
    // (filter on laneStackHost) ran AFTER ScrollPane's behaviour and was
    // a no-op for trackpad two-finger gestures on macOS.

    private void wireScrollAndZoom(Region laneStackHost) {
        addEventFilter(ScrollEvent.SCROLL, this::onScroll);
        addEventFilter(ZoomEvent.ZOOM,     this::onZoomGesture);
    }

    private void onScroll(ScrollEvent e) {
        // Only honour scrolls that originate inside the lane area or one of
        // the strips above it — we don't want to hijack scroll on the
        // header column or output band.
        if (!eventOverLaneArea(e)) return;

        double anchor = mouseTimeFromEvent(e);
        // Shift-wheel pans; everything else zooms. SolidWorks / Blender /
        // most CAD apps zoom on plain scroll because there's no useful
        // vertical content in a horizontal timeline.
        if (e.isShiftDown()) {
            double pxDelta = e.getDeltaX() != 0 ? e.getDeltaX() : e.getDeltaY();
            double k = metrics.pxPerMicro.get();
            if (pxDelta != 0 && k > 0) {
                metrics.timeAxis.viewport.panBy(-pxDelta / k);
            }
        } else {
            double dy = e.getDeltaY() != 0 ? e.getDeltaY() : -e.getDeltaX();
            if (dy == 0) return;
            double factor = dy > 0 ? TimeAxis.ZOOM_IN_FACTOR : TimeAxis.ZOOM_OUT_FACTOR;
            metrics.timeAxis.viewport.zoomAround(anchor, factor);
        }
        e.consume();
    }

    private void onZoomGesture(ZoomEvent e) {
        if (!eventOverLaneArea(e)) return;
        double anchor = mouseTimeFromEvent(e);
        metrics.timeAxis.viewport.zoomAround(anchor, 1.0 / e.getZoomFactor());
        e.consume();
    }

    /** Return true if the event lies within the lane area (excludes header column + output band). */
    private boolean eventOverLaneArea(javafx.scene.input.GestureEvent e) {
        // We accept events anywhere in the timeline root, but must exclude
        // the header column on the left so users can scroll-zoom even when
        // the mouse is over the strip area or the output band.
        var headerBounds = headerColumn.localToScene(headerColumn.getBoundsInLocal());
        return !headerBounds.contains(e.getSceneX(), e.getSceneY());
    }

    /** Convert a gesture-event scene-X to a project time, anchored to the lane area. */
    private double mouseTimeFromEvent(javafx.scene.input.GestureEvent e) {
        var laneHost = laneStack.getParent();
        if (laneHost == null) return metrics.xToTime(e.getX());
        var local = laneHost.sceneToLocal(e.getSceneX(), e.getSceneY());
        return metrics.xToTime(local.getX());
    }

    /**
     * Build the two top strips — the mini viewport-bounds strip (top) above
     * the DAW main strip (analysis-window + cursor). Both align with the
     * lane area's left edge via a fixed-width gutter spacer; binding to
     * {@code headerColumn.widthProperty()} caused initial-layout creep, so
     * a hardcoded 180 px matches the locked header column.
     *
     * <p>Layout (top to bottom):
     * <pre>
     *   ┌── gutter ──┬─ ViewportMiniStrip (sets DAW visible bounds) ─┐  18 px
     *   ├────────────┼──────────────────────────────────────────────┤
     *   │            │  DawScrubStrip (cursor + analysis window)    │  36 px
     * </pre>
     */
    private VBox buildTopStrips() {
        var miniRow = new HBox(buildGutterSpacer(), viewportMiniStrip);
        HBox.setHgrow(viewportMiniStrip, Priority.ALWAYS);
        miniRow.getStyleClass().add("viewport-mini-row");

        var dawRow = new HBox(buildGutterSpacer(), dawScrubStrip);
        HBox.setHgrow(dawScrubStrip, Priority.ALWAYS);
        dawRow.getStyleClass().add("daw-strip-row");

        var stack = new VBox(miniRow, dawRow);
        stack.getStyleClass().add("timeline-top-strips");
        return stack;
    }

    private Region buildGutterSpacer() {
        var spacer = new Region();
        spacer.setMinWidth(180);
        spacer.setPrefWidth(180);
        spacer.setMaxWidth(180);
        spacer.getStyleClass().add("time-axis-gutter");
        return spacer;
    }
}
