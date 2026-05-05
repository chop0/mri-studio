package ax.xz.mri.ui.workbench.pane.timeline;

import ax.xz.mri.model.sequence.ClipKind;
import ax.xz.mri.model.sequence.ClipShape;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.model.sequence.Track;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import ax.xz.mri.ui.viewmodel.ViewportViewModel;
import ax.xz.mri.util.MathUtil;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;

import java.util.LinkedHashSet;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/**
 * Handles all mouse interaction on the timeline canvas.
 *
 * <p>Owns the in-progress {@link ClipDragState}, translates raw mouse events
 * into session mutations, runs hit tests, and builds the context menu.
 * {@link TimelineRenderer} reads the drag state to paint overlays (rubber
 * band, create-clip preview, snap guide).
 */
public final class TimelineInteraction {

    private static final double EDGE_HIT_WIDTH = 8;
    private static final double SPLINE_HIT_RADIUS = 6;
    private static final double COLLAPSE_HIT_X = 2;
    private static final double COLLAPSE_HIT_W = 14;
    /** Vertical thickness (px, half above + half below) of the resize hit band on the output-band divider. */
    private static final double DIVIDER_HIT_PAD = 4;

    private final SequenceEditSession session;
    private final ViewportViewModel viewport;
    private final Supplier<TimelineGeometry> geometrySupplier;
    private final Runnable onRedraw;
    private final Consumer<Cursor> onCursor;
    /** Sink for time-axis scrubbing — pushed the new cursor time (μs) on drag. May be null. */
    private DoubleConsumer onScrub = t -> {};

    private ClipDragState drag = ClipDragState.IDLE;
    private double snapGuideTime = Double.NaN;
    private ClipKind activeCreationKind;
    private ContextMenu activeMenu;
    /** Set true while a primary-button drag started in the time-axis strip — drives playhead scrubbing. */
    private boolean scrubbing;
    /** Clip currently hovered with Alt held — drives the vertical double-arrow amplitude affordance overlay. */
    private String altHoverClipId;

    public TimelineInteraction(SequenceEditSession session,
                               ViewportViewModel viewport,
                               Supplier<TimelineGeometry> geometrySupplier,
                               Runnable onRedraw,
                               Consumer<Cursor> onCursor) {
        this.session = session;
        this.viewport = viewport;
        this.geometrySupplier = geometrySupplier;
        this.onRedraw = onRedraw;
        this.onCursor = onCursor;
    }

    /** Wire the time-axis scrub sink (called by the canvas after construction). */
    public void setOnScrub(DoubleConsumer onScrub) {
        this.onScrub = onScrub != null ? onScrub : t -> {};
    }

    public ClipDragState drag()       { return drag; }
    public double snapGuideTime()     { return snapGuideTime; }
    public String altHoverClipId()    { return altHoverClipId; }
    public void setActiveCreationKind(ClipKind kind) { this.activeCreationKind = kind; }

    // ══════════════════════════════════════════════════════════════════════════
    // Mouse event entry points
    // ══════════════════════════════════════════════════════════════════════════

    public void onPress(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) return;
        dismissContextMenu();
        var geom = geometrySupplier.get();
        double mx = e.getX(), my = e.getY();

        // Output-band divider drag: grab the line at the top of the
        // read-only output band and resize the per-row height. Tested
        // BEFORE the time-axis strip because the divider would otherwise
        // be shadowed by the strip when the band is empty.
        if (isOnOutputBandDivider(geom, my)) {
            drag = new ClipDragState.ResizeOutputBand(my,
                session.outputRowHeight.get(),
                Math.max(1, geom.outputRows().size()));
            onCursor.accept(Cursor.V_RESIZE);
            return;
        }

        // Time-axis strip click: scrub the playhead. The strip lives below
        // the editable + output bands, between plotBottom and the canvas
        // height. A click sets the cursor; subsequent drag motion keeps
        // updating it via the same scrubbing flag.
        if (my >= geom.plotBottom() && mx >= geom.plotLeft() && mx <= geom.plotRight()) {
            scrubbing = true;
            onCursor.accept(Cursor.H_RESIZE);
            scrubTo(geom, mx);
            return;
        }

        // Label column: collapse button, double-click to rename, drag to reorder.
        if (mx < geom.labelWidth()) {
            int ti = geom.trackAtY(my);
            if (ti < 0) return;
            var track = geom.tracks().get(ti);
            if (inCollapseButton(mx)) {
                session.setTrackCollapsed(track.id(), !geom.isCollapsed(track));
                return;
            }
            if (e.getClickCount() >= 2) {
                promptRenameTrack(track);
                return;
            }
            // Single press anywhere else in the label arms a track-reorder
            // drag. The drop position is recomputed each onDrag step from
            // mouse Y; release commits via SequenceEditSession.reorderTrack.
            drag = new ClipDragState.MoveTrack(track.id(), ti, ti);
            onCursor.accept(Cursor.MOVE);
            onRedraw.run();
            return;
        }

        var hit = hitTest(geom, mx, my);
        if (hit != null) {
            startClipDrag(e, geom, hit);
            return;
        }

        if (activeCreationKind != null) {
            int ti = geom.trackAtY(my);
            if (ti >= 0 && !geom.isCollapsed(geom.tracks().get(ti))) {
                drag = new ClipDragState.CreateClip(
                    activeCreationKind, geom.tracks().get(ti).id(),
                    geom.xToTime(mx), mx);
                onCursor.accept(Cursor.CROSSHAIR);
                onRedraw.run();
            }
            return;
        }

        if (e.isAltDown() || e.isMiddleButtonDown()) {
            drag = new ClipDragState.PanView(geom.xToTime(mx), viewport.vS.get());
            onCursor.accept(Cursor.CLOSED_HAND);
            return;
        }

        if (!e.isShiftDown() && !e.isShortcutDown()) session.clearSelection();
        drag = new ClipDragState.RubberBand(mx, my, mx, my);
        onCursor.accept(Cursor.CROSSHAIR);
        onRedraw.run();
    }

    public void onDrag(MouseEvent e) {
        var geom = geometrySupplier.get();
        double mx = e.getX(), my = e.getY();
        snapGuideTime = Double.NaN;

        if (scrubbing) {
            scrubTo(geom, mx);
            onRedraw.run();
            return;
        }

        switch (drag) {
            case ClipDragState.Move m -> onMove(geom, m, mx, my);
            case ClipDragState.ResizeLeft rl -> onResizeLeft(geom, rl, mx);
            case ClipDragState.ResizeRight rr -> onResizeRight(geom, rr, mx);
            case ClipDragState.Amplitude a -> onAmplitude(geom, a, my);
            case ClipDragState.SplinePoint sp -> onSplinePoint(geom, sp, mx, my);
            case ClipDragState.PanView p -> onPan(geom, p, mx);
            case ClipDragState.ResizeOutputBand rob -> onResizeOutputBand(rob, my);
            case ClipDragState.MoveTrack mt ->
                drag = new ClipDragState.MoveTrack(mt.trackId(), mt.originIndex(), dropIndexAtY(geom, my));
            case ClipDragState.CreateClip c ->
                drag = new ClipDragState.CreateClip(c.kind(), c.trackId(), c.startTimeMicros(), mx);
            case ClipDragState.RubberBand rb ->
                drag = new ClipDragState.RubberBand(rb.startX(), rb.startY(), mx, my);
            case ClipDragState.Idle ignored -> { /* nothing */ }
        }
        onRedraw.run();
    }

    public void onRelease(MouseEvent e) {
        snapGuideTime = Double.NaN;
        var geom = geometrySupplier.get();

        if (scrubbing) {
            scrubbing = false;
            onCursor.accept(Cursor.DEFAULT);
            onRedraw.run();
            return;
        }

        switch (drag) {
            case ClipDragState.CreateClip c -> finishCreate(c, geom, e.getX());
            case ClipDragState.RubberBand rb -> finishRubberBand(rb, geom, e);
            case ClipDragState.MoveTrack mt -> {
                if (mt.dropIndex() != mt.originIndex()) session.reorderTrack(mt.trackId(), mt.dropIndex());
            }
            // Coalesced drags (Move / Resize / Amplitude / SplinePoint) commit
            // their accumulated mutations as a single undo entry.
            case ClipDragState.Move m -> session.endTransaction();
            case ClipDragState.ResizeLeft rl -> session.endTransaction();
            case ClipDragState.ResizeRight rr -> session.endTransaction();
            case ClipDragState.Amplitude a -> session.endTransaction();
            case ClipDragState.SplinePoint sp -> session.endTransaction();
            default -> { /* nothing */ }
        }

        drag = ClipDragState.IDLE;
        onCursor.accept(Cursor.DEFAULT);
        onRedraw.run();
    }

    /** Push a new cursor time clamped to the sequence's bounds, snapping to the editor grid. */
    private void scrubTo(TimelineGeometry geom, double mouseX) {
        double t = geom.xToTime(mouseX);
        // Clamp to [0, totalDuration] so the playhead can't escape the edited
        // region, then snap (the editor's own snapTime honours the user's
        // snap settings — same UX as clip dragging).
        t = Math.max(0, Math.min(session.totalDuration.get(), t));
        if (session.snapEnabled.get()) t = session.snapTime(t);
        onScrub.accept(t);
    }

    public void onMouseMoved(MouseEvent e) {
        var geom = geometrySupplier.get();
        double mx = e.getX(), my = e.getY();
        // Output-band divider takes priority over the label column so the
        // resize cursor still shows when the user reaches into the label
        // gutter to grab the divider.
        if (isOnOutputBandDivider(geom, my)) {
            onCursor.accept(Cursor.V_RESIZE);
            return;
        }
        if (mx < geom.labelWidth()) {
            // Collapse button keeps the default pointer; the rest of the
            // label is grabbable for drag-to-reorder.
            onCursor.accept(inCollapseButton(mx) ? Cursor.DEFAULT : Cursor.OPEN_HAND);
            return;
        }
        // Hovering the time-axis strip shows the H_RESIZE cursor as a hint
        // that this region is the playhead scrub bar.
        if (my >= geom.plotBottom()) {
            onCursor.accept(Cursor.H_RESIZE);
            return;
        }
        var hit = hitTest(geom, mx, my);
        String prevAltHover = altHoverClipId;
        altHoverClipId = (hit != null && hit.zone == HitZone.BODY && e.isAltDown()) ? hit.clipId : null;
        if (!java.util.Objects.equals(prevAltHover, altHoverClipId)) onRedraw.run();
        if (hit != null) {
            onCursor.accept(switch (hit.zone) {
                case LEFT_EDGE, RIGHT_EDGE -> Cursor.H_RESIZE;
                case SPLINE_POINT -> Cursor.CROSSHAIR;
                case BODY -> e.isAltDown() ? Cursor.V_RESIZE : Cursor.HAND;
            });
        } else if (activeCreationKind != null) {
            onCursor.accept(Cursor.CROSSHAIR);
        } else {
            onCursor.accept(Cursor.DEFAULT);
        }
    }

    public void onScroll(ScrollEvent e) {
        var geom = geometrySupplier.get();
        // ⌘/Ctrl + wheel zooms toward the cursor; bare wheel pans
        // horizontally. Trackpad horizontal swipes (deltaX) also pan, in case
        // the user prefers two-finger swipe over modified scroll.
        if (e.isShortcutDown()) {
            viewport.zoomViewportAround(geom.xToTime(e.getX()),
                e.getDeltaY() > 0 ? 0.8 : 1.25);
            return;
        }
        double pxDelta = e.getDeltaX() != 0 ? e.getDeltaX() : e.getDeltaY();
        if (pxDelta == 0) return;
        double span = viewport.vE.get() - viewport.vS.get();
        double width = Math.max(1, geom.plotWidth());
        double deltaTime = -pxDelta * span / width;
        double max = Math.max(viewport.maxTime.get(), 1);
        double newStart = MathUtil.clamp(viewport.vS.get() + deltaTime, 0, Math.max(0, max - span));
        viewport.setViewport(newStart, newStart + span);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Drag start / update / finish helpers
    // ══════════════════════════════════════════════════════════════════════════

    private void startClipDrag(MouseEvent e, TimelineGeometry geom, ClipHit hit) {
        var clip = session.findClip(hit.clipId);
        if (clip == null) return;

        if (e.isShortcutDown())        session.toggleSelection(hit.clipId);
        else if (e.isShiftDown())      session.addToSelection(hit.clipId);
        else if (!session.isSelected(hit.clipId)) session.selectOnly(hit.clipId);
        else                           session.primarySelectedClipId.set(hit.clipId);

        if (e.isAltDown() && hit.zone == HitZone.BODY) {
            drag = new ClipDragState.Amplitude(hit.clipId, e.getY(), hit.trackIndex, clip.amplitude());
            onCursor.accept(Cursor.V_RESIZE);
            session.beginTransaction("Adjust amplitude");
            return;
        }
        switch (hit.zone) {
            case LEFT_EDGE -> {
                drag = new ClipDragState.ResizeLeft(hit.clipId);
                onCursor.accept(Cursor.H_RESIZE);
                session.beginTransaction("Resize clip");
            }
            case RIGHT_EDGE -> {
                drag = new ClipDragState.ResizeRight(hit.clipId);
                onCursor.accept(Cursor.H_RESIZE);
                session.beginTransaction("Resize clip");
            }
            case SPLINE_POINT -> {
                drag = new ClipDragState.SplinePoint(hit.clipId, hit.splinePointIndex);
                onCursor.accept(Cursor.CROSSHAIR);
                session.beginTransaction("Move spline point");
            }
            case BODY -> {
                double mx = e.getX();
                double anchorTime = geom.xToTime(mx);
                boolean multi = session.selectedClipIds.size() > 1 && session.isSelected(hit.clipId);
                drag = new ClipDragState.Move(
                    hit.clipId,
                    anchorTime - clip.startTime(),
                    hit.trackIndex, multi, anchorTime
                );
                onCursor.accept(Cursor.CLOSED_HAND);
                session.beginTransaction(multi ? "Move clips" : "Move clip");
            }
        }
    }

    private void onMove(TimelineGeometry geom, ClipDragState.Move m, double mx, double my) {
        var clip = session.findClip(m.primaryClipId());
        if (clip == null) return;

        if (m.multi()) {
            double now = geom.xToTime(mx);
            double delta = now - m.multiAnchorTimeMicros();
            if (session.snapEnabled.get()) {
                double rawStart = clip.startTime() + delta;
                double snapped = session.snapTime(rawStart);
                delta += snapped - rawStart;
                if (snapped != rawStart) snapGuideTime = snapped;
            }
            session.moveSelectedClips(delta);
            drag = new ClipDragState.Move(m.primaryClipId(), m.anchorOffsetMicros(),
                m.originTrackIndex(), true, now);
        } else {
            double newStart = Math.max(0, geom.xToTime(mx) - m.anchorOffsetMicros());
            if (session.snapEnabled.get()) {
                double snapped = session.snapTime(newStart);
                if (snapped != newStart) snapGuideTime = snapped;
                newStart = snapped;
            }
            session.moveClip(m.primaryClipId(), newStart);
        }

        // Cross-track: dragging into a different lane re-routes the clip.
        int ti = geom.trackAtY(my);
        if (ti >= 0 && ti != m.originTrackIndex()) {
            var newTrack = geom.tracks().get(ti);
            session.changeClipTrack(m.primaryClipId(), newTrack.id());
            drag = new ClipDragState.Move(m.primaryClipId(), m.anchorOffsetMicros(),
                ti, m.multi(), m.multiAnchorTimeMicros());
        }
    }

    private void onResizeLeft(TimelineGeometry geom, ClipDragState.ResizeLeft rl, double mx) {
        double newStart = geom.xToTime(mx);
        if (session.snapEnabled.get()) {
            double snapped = session.snapTime(newStart);
            if (snapped != newStart) snapGuideTime = snapped;
            newStart = snapped;
        }
        session.resizeClipLeft(rl.clipId(), newStart);
    }

    private void onResizeRight(TimelineGeometry geom, ClipDragState.ResizeRight rr, double mx) {
        double newEnd = geom.xToTime(mx);
        if (session.snapEnabled.get()) {
            double snapped = session.snapTime(newEnd);
            if (snapped != newEnd) snapGuideTime = snapped;
            newEnd = snapped;
        }
        var clip = session.findClip(rr.clipId());
        if (clip == null) return;
        session.resizeClip(rr.clipId(), Math.max(session.dt.get(), newEnd - clip.startTime()));
    }

    private void onAmplitude(TimelineGeometry geom, ClipDragState.Amplitude a, double my) {
        double deltaY = a.anchorY() - my;
        double h = geom.trackHeight(a.trackIndex());
        var track = geom.tracks().get(a.trackIndex());
        double displayMax = TimelineRenderer.channelHardwareMax(session, track.simChannel());
        double deltaAmp = (deltaY / (h * 0.42)) * displayMax;
        session.setClipAmplitude(a.clipId(), a.originalAmplitude() + deltaAmp);
    }

    private void onSplinePoint(TimelineGeometry geom, ClipDragState.SplinePoint sp, double mx, double my) {
        var clip = session.findClip(sp.clipId());
        if (clip == null || !(clip.shape() instanceof ClipShape.Spline)) return;
        int ti = geom.trackAtY(my);
        if (ti < 0) ti = 0;
        double t = (geom.xToTime(mx) - clip.startTime()) / clip.duration();
        t = MathUtil.clamp01(t);
        var outputChannel = geom.tracks().get(ti).simChannel();
        double displayMax = TimelineRenderer.channelDisplayMax(session, outputChannel);
        double v = clip.amplitude() != 0 ? geom.yToValue(ti, my, displayMax) / clip.amplitude() : 0;
        session.updateSplinePoint(sp.clipId(), sp.pointIndex(), new ClipShape.Spline.Point(t, v));
    }

    private void onPan(TimelineGeometry geom, ClipDragState.PanView p, double mx) {
        double span = viewport.vE.get() - viewport.vS.get();
        double mouseFraction = (mx - geom.plotLeft()) / geom.plotWidth();
        double mouseTimeInOriginalView = p.viewStartAnchorMicros() + mouseFraction * span;
        double offset = p.anchorTimeMicros() - mouseTimeInOriginalView;
        double newStart = p.viewStartAnchorMicros() + offset;
        viewport.setViewport(newStart, newStart + span);
    }

    /**
     * Adjust per-row output height as the user drags the divider above the
     * output band. Dragging up grows each row; dragging down shrinks. The
     * effective per-row height is the original height plus the total
     * vertical mouse travel divided by the row count, clamped to safe
     * bounds.
     */
    private void onResizeOutputBand(ClipDragState.ResizeOutputBand rob, double my) {
        double deltaY = rob.anchorY() - my;
        double newRowHeight = rob.originalRowHeight() + deltaY / rob.rowCount();
        newRowHeight = Math.max(TimelineCanvas.OUTPUT_ROW_MIN_HEIGHT,
            Math.min(TimelineCanvas.OUTPUT_ROW_MAX_HEIGHT, newRowHeight));
        session.outputRowHeight.set(newRowHeight);
    }

    /**
     * The divider line above the read-only output band is a draggable
     * resize handle. Active only when the band actually has rows — with no
     * traces enabled, the band collapses and the divider is meaningless.
     */
    private boolean isOnOutputBandDivider(TimelineGeometry geom, double my) {
        if (geom.outputRows().isEmpty()) return false;
        double dividerY = geom.outputBandTop();
        return Math.abs(my - dividerY) <= DIVIDER_HIT_PAD;
    }

    private void finishCreate(ClipDragState.CreateClip c, TimelineGeometry geom, double endX) {
        if (activeCreationKind == null) return;
        double endTime = geom.xToTime(endX);
        double start = Math.min(c.startTimeMicros(), endTime);
        double end = Math.max(c.startTimeMicros(), endTime);
        if (session.snapEnabled.get()) {
            start = session.snapTime(start);
            end = session.snapTime(end);
        }
        double duration = end - start;
        if (duration >= session.dt.get()) {
            session.addClip(session.createClipCentred(c.kind(), c.trackId(), start, duration));
        }
    }

    private void finishRubberBand(ClipDragState.RubberBand rb, TimelineGeometry geom, MouseEvent e) {
        double tStart = geom.xToTime(Math.min(rb.startX(), rb.endX()));
        double tEnd = geom.xToTime(Math.max(rb.startX(), rb.endX()));
        double yMin = Math.min(rb.startY(), rb.endY());
        double yMax = Math.max(rb.startY(), rb.endY());
        var trackIds = new LinkedHashSet<String>();
        for (int i = 0; i < geom.tracks().size(); i++) {
            double top = geom.trackTop(i);
            double h = geom.trackHeight(i);
            if (top + h > yMin && top < yMax) trackIds.add(geom.tracks().get(i).id());
        }
        boolean addToExisting = e.isShiftDown() || e.isShortcutDown();
        session.selectClipsInRegion(tStart, tEnd, trackIds, addToExisting);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Hit testing
    // ══════════════════════════════════════════════════════════════════════════

    public enum HitZone { BODY, LEFT_EDGE, RIGHT_EDGE, SPLINE_POINT }

    public record ClipHit(String clipId, HitZone zone, int splinePointIndex, int trackIndex) {}

    public ClipHit hitTest(TimelineGeometry geom, double mx, double my) {
        int ti = geom.trackAtY(my);
        if (ti < 0) return null;
        var track = geom.tracks().get(ti);
        if (geom.isCollapsed(track)) return null;

        double top = geom.trackTop(ti);
        double h = geom.trackHeight(ti);
        double displayMax = TimelineRenderer.channelDisplayMax(session, track.simChannel());

        var trackClips = session.clipsOnTrack(track.id());
        for (int i = trackClips.size() - 1; i >= 0; i--) {
            var clip = trackClips.get(i);
            double x1 = geom.timeToX(clip.startTime());
            double x2 = geom.timeToX(clip.endTime());
            if (mx < x1 - EDGE_HIT_WIDTH || mx > x2 + EDGE_HIT_WIDTH) continue;
            if (my < top || my > top + h) continue;

            if (session.isSelected(clip.id()) && clip.shape() instanceof ClipShape.Spline spline) {
                for (int sp = 0; sp < spline.points().size(); sp++) {
                    var pt = spline.points().get(sp);
                    double px = geom.timeToX(clip.startTime() + pt.t() * clip.duration());
                    double py = geom.valueToY(ti, pt.value() * clip.amplitude(), displayMax);
                    if (Math.abs(mx - px) <= SPLINE_HIT_RADIUS && Math.abs(my - py) <= SPLINE_HIT_RADIUS) {
                        return new ClipHit(clip.id(), HitZone.SPLINE_POINT, sp, ti);
                    }
                }
            }
            if (Math.abs(mx - x1) <= EDGE_HIT_WIDTH) return new ClipHit(clip.id(), HitZone.LEFT_EDGE, -1, ti);
            if (Math.abs(mx - x2) <= EDGE_HIT_WIDTH) return new ClipHit(clip.id(), HitZone.RIGHT_EDGE, -1, ti);
            if (mx >= x1 && mx <= x2) return new ClipHit(clip.id(), HitZone.BODY, -1, ti);
        }
        return null;
    }

    private boolean inCollapseButton(double mx) {
        return mx >= COLLAPSE_HIT_X && mx <= COLLAPSE_HIT_X + COLLAPSE_HIT_W;
    }

    /**
     * Index a track-reorder drag would land on if released at this Y.
     * Above the first lane → 0; below the last → end. Within the stack,
     * inserts above whichever lane the mouse is in the upper half of.
     */
    private static int dropIndexAtY(TimelineGeometry geom, double my) {
        int n = geom.tracks().size();
        if (n == 0) return 0;
        for (int i = 0; i < n; i++) {
            double top = geom.trackTop(i);
            double mid = top + geom.trackHeight(i) / 2.0;
            if (my < mid) return i;
        }
        return n - 1;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Context menu
    // ══════════════════════════════════════════════════════════════════════════

    public void onContextMenu(Node owner, ContextMenuEvent e) {
        dismissContextMenu();
        var geom = geometrySupplier.get();
        double mx = e.getX(), my = e.getY();
        var menu = buildContextMenu(geom, mx, my);
        if (menu.getItems().isEmpty()) return;
        activeMenu = menu;
        menu.show(owner, e.getScreenX(), e.getScreenY());
    }

    public void dismissContextMenu() {
        if (activeMenu != null && activeMenu.isShowing()) activeMenu.hide();
        activeMenu = null;
    }

    private ContextMenu buildContextMenu(TimelineGeometry geom, double mx, double my) {
        var menu = new ContextMenu();
        menu.setAutoHide(true);

        if (mx < geom.labelWidth()) {
            int ti = geom.trackAtY(my);
            if (ti >= 0) {
                var track = geom.tracks().get(ti);
                populateTrackMenu(menu, track, geom);
            }
            return menu;
        }

        var hit = hitTest(geom, mx, my);
        if (hit != null) {
            populateClipMenu(menu, geom, mx, my, hit);
            return menu;
        }

        // Right-clicked on empty space within a lane.
        int ti = geom.trackAtY(my);
        if (ti >= 0 && !geom.isCollapsed(geom.tracks().get(ti))) {
            populateEmptyLaneMenu(menu, geom.tracks().get(ti), geom.xToTime(mx));
        }

        var zoomFit = new MenuItem("Zoom to Fit");
        zoomFit.setOnAction(ev -> viewport.fitViewportToData());
        menu.getItems().add(zoomFit);

        // Append a global "Add Track…" submenu for when right-clicking in a
        // blank area with no hits.
        if (menu.getItems().size() <= 1) {
            menu.getItems().addAll(new SeparatorMenuItem(), buildAddTrackMenu("Add New Track"));
        }
        return menu;
    }

    private void populateTrackMenu(ContextMenu menu, Track track, TimelineGeometry geom) {
        var toggle = new MenuItem(geom.isCollapsed(track) ? "Expand Track" : "Collapse Track");
        toggle.setOnAction(ev -> session.setTrackCollapsed(track.id(), !geom.isCollapsed(track)));

        var rename = new MenuItem("Rename Track\u2026");
        rename.setOnAction(ev -> promptRenameTrack(track));

        var collapseAll = new MenuItem("Collapse All");
        collapseAll.setOnAction(ev -> { for (var t : geom.tracks()) session.setTrackCollapsed(t.id(), true); });
        var expandAll = new MenuItem("Expand All");
        expandAll.setOnAction(ev -> { for (var t : geom.tracks()) session.setTrackCollapsed(t.id(), false); });

        var routeMenu = buildRouteTrackMenu(track);
        var hardwareRouteMenu = buildHardwareRouteMenu(track);
        var addTrackMenu = buildAddTrackMenu("Add Track");

        var duplicate = new MenuItem("Duplicate Track");
        duplicate.setOnAction(ev -> {
            var copy = session.addTrack(track.simChannel(), track.name() + " copy");
            // Copy the original's clips onto the new track.
            for (var c : session.clipsOnTrack(track.id())) {
                session.addClip(c.withNewId().withTrack(copy.id()));
            }
        });

        var remove = new MenuItem("Remove Track");
        remove.setOnAction(ev -> session.removeTrack(track.id()));

        menu.getItems().addAll(
            toggle, rename,
            new SeparatorMenuItem(),
            routeMenu, hardwareRouteMenu, addTrackMenu, duplicate,
            new SeparatorMenuItem(),
            collapseAll, expandAll,
            new SeparatorMenuItem(),
            remove
        );
    }

    private Menu buildRouteTrackMenu(Track track) {
        // "Send to …" reads naturally even when the user hasn't internalised the
        // DAW track / circuit voltage-source split: every item is the name of a
        // circuit source this track's waveform will drive.
        var menu = new Menu("Send to voltage source (sim)");
        for (var channel : session.availableOutputChannels()) {
            var name = session.defaultTrackNameFor(channel);
            var item = new MenuItem(name);
            item.setDisable(channel.equals(track.simChannel()));
            item.setOnAction(ev -> session.setTrackSimChannel(track.id(), channel));
            menu.getItems().add(item);
        }
        if (menu.getItems().isEmpty()) menu.setDisable(true);
        return menu;
    }

    private Menu buildHardwareRouteMenu(Track track) {
        var hwConfig = session.activeHardwareConfigDoc();
        var menu = new Menu("Send to hardware output");
        if (hwConfig == null) {
            var hint = new MenuItem("(no hardware config bound)");
            hint.setDisable(true);
            menu.getItems().add(hint);
            return menu;
        }
        var channels = session.availableHardwareChannels(hwConfig);
        if (channels.isEmpty()) {
            var hint = new MenuItem("(plugin exposes no outputs)");
            hint.setDisable(true);
            menu.getItems().add(hint);
            return menu;
        }
        var clearItem = new MenuItem("(unrouted)");
        clearItem.setDisable(track.hardwareChannel() == null);
        clearItem.setOnAction(ev -> session.setTrackHardwareChannel(track.id(), null));
        menu.getItems().add(clearItem);
        menu.getItems().add(new SeparatorMenuItem());
        for (var channel : channels) {
            var label = channel.subIndex() == 0 ? channel.sourceName()
                                                : channel.sourceName() + "[" + channel.subIndex() + "]";
            var item = new MenuItem(label);
            item.setDisable(channel.equals(track.hardwareChannel()));
            item.setOnAction(ev -> session.setTrackHardwareChannel(track.id(), channel));
            menu.getItems().add(item);
        }
        return menu;
    }

    private Menu buildAddTrackMenu(String label) {
        var menu = new Menu(label);
        for (var channel : session.availableOutputChannels()) {
            var name = session.defaultTrackNameFor(channel);
            var item = new MenuItem(name);
            item.setOnAction(ev -> session.addTrack(channel, name));
            menu.getItems().add(item);
        }
        if (menu.getItems().isEmpty()) menu.setDisable(true);
        return menu;
    }

    private void populateClipMenu(ContextMenu menu, TimelineGeometry geom, double mx, double my, ClipHit hit) {
        if (!session.isSelected(hit.clipId)) session.selectOnly(hit.clipId);
        var clip = session.findClip(hit.clipId);
        if (clip == null) return;

        var delete = new MenuItem("Delete");
        delete.setOnAction(ev -> session.deleteSelectedClips());
        var duplicate = new MenuItem("Duplicate");
        duplicate.setOnAction(ev -> session.duplicateSelectedClips());
        var split = new MenuItem("Split Clip Here");
        split.setOnAction(ev -> session.splitClip(hit.clipId, geom.xToTime(mx)));
        var recentre = new MenuItem("Re-centre Media");
        recentre.setOnAction(ev -> session.recentreClip(hit.clipId));

        var shapes = new Menu("Change Shape");
        for (var kind : ClipKind.values()) {
            var item = new MenuItem(kind.displayName());
            item.setDisable(kind == clip.shape().kind());
            item.setOnAction(ev -> session.changeClipKind(hit.clipId, kind));
            shapes.getItems().add(item);
        }

        menu.getItems().addAll(delete, duplicate, split, recentre, new SeparatorMenuItem(), shapes);

        if (clip.shape() instanceof ClipShape.Spline) {
            menu.getItems().add(new SeparatorMenuItem());
            var addPoint = new MenuItem("Add Spline Point Here");
            addPoint.setOnAction(ev -> addSplinePointAt(clip, geom, mx, my));
            menu.getItems().add(addPoint);
            if (hit.zone == HitZone.SPLINE_POINT && hit.splinePointIndex >= 0) {
                var removePoint = new MenuItem("Remove This Point");
                removePoint.setOnAction(ev -> session.removeSplinePoint(hit.clipId, hit.splinePointIndex));
                menu.getItems().add(removePoint);
            }
        }
    }

    private void populateEmptyLaneMenu(ContextMenu menu, Track track, double time) {
        var addMenu = new Menu("Add Clip");
        for (var kind : ClipKind.values()) {
            var item = new MenuItem(kind.displayName());
            item.setOnAction(ev -> session.addClip(session.createDefaultClip(kind, track.id(), time)));
            addMenu.getItems().add(item);
        }
        menu.getItems().add(addMenu);
    }

    private void addSplinePointAt(SignalClip clip, TimelineGeometry geom, double mx, double my) {
        double t = (geom.xToTime(mx) - clip.startTime()) / clip.duration();
        t = MathUtil.clamp01(t);
        int ti = geom.trackAtY(my);
        SequenceChannel outputChannel = ti >= 0 ? geom.tracks().get(ti).simChannel() : null;
        double displayMax = outputChannel != null
            ? TimelineRenderer.channelDisplayMax(session, outputChannel)
            : 1.0;
        double v = ti >= 0 && clip.amplitude() != 0
            ? geom.yToValue(ti, my, displayMax) / clip.amplitude()
            : 0.5;
        session.addSplinePoint(clip.id(), new ClipShape.Spline.Point(t, v));
    }

    private void promptRenameTrack(Track track) {
        var dialog = new TextInputDialog(track.name());
        dialog.setTitle("Rename Track");
        dialog.setHeaderText("Rename track");
        dialog.setContentText("Name:");
        dialog.showAndWait().map(String::trim).filter(s -> !s.isBlank())
            .ifPresent(name -> session.renameTrack(track.id(), name));
    }
}
