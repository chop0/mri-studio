package ax.xz.mri.ui.workbench.pane.timeline;

import ax.xz.mri.model.sequence.ClipKind;
import ax.xz.mri.model.sequence.ClipShape;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.ui.viewmodel.EditorTrack;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;

import java.util.LinkedHashSet;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Handles all mouse interaction on the timeline canvas.
 *
 * <p>Owns the in-progress {@link ClipDragState}, translates raw mouse events
 * into session mutations, runs hit tests, and builds the context menu.
 * {@link TimelineRenderer} reads the drag state to paint overlays (rubber
 * band, create-clip preview, snap guide).
 *
 * <p>Clean separation of concerns: this class never paints; the renderer
 * never mutates. The canvas is a thin shell that wires mouse events to this
 * class and schedules redraws via the supplied {@code onRedraw} hook.
 */
public final class TimelineInteraction {

    private static final double EDGE_HIT_WIDTH = 6;
    private static final double SPLINE_HIT_RADIUS = 6;
    private static final double COLLAPSE_HIT_X = 2;
    private static final double COLLAPSE_HIT_W = 14;

    private final SequenceEditSession session;
    private final Supplier<TimelineGeometry> geometrySupplier;
    private final Runnable onRedraw;
    private final Consumer<Cursor> onCursor;

    private ClipDragState drag = ClipDragState.IDLE;
    private double snapGuideTime = Double.NaN;
    private ClipKind activeCreationKind;
    private ContextMenu activeMenu;

    public TimelineInteraction(SequenceEditSession session,
                               Supplier<TimelineGeometry> geometrySupplier,
                               Runnable onRedraw,
                               Consumer<Cursor> onCursor) {
        this.session = session;
        this.geometrySupplier = geometrySupplier;
        this.onRedraw = onRedraw;
        this.onCursor = onCursor;
    }

    public ClipDragState drag()       { return drag; }
    public double snapGuideTime()     { return snapGuideTime; }
    public void setActiveCreationKind(ClipKind kind) { this.activeCreationKind = kind; }

    // ══════════════════════════════════════════════════════════════════════════
    // Mouse event entry points
    // ══════════════════════════════════════════════════════════════════════════

    public void onPress(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) return;
        dismissContextMenu();
        var geom = geometrySupplier.get();
        double mx = e.getX(), my = e.getY();

        // Click in the label column: toggle collapse, or enter reorder mode
        if (mx < geom.labelWidth()) {
            int ti = geom.trackAtY(my);
            if (ti >= 0) {
                var track = geom.tracks().get(ti);
                if (inCollapseButton(geom, track, ti, mx)) {
                    session.setChannelCollapsed(track.channel(), !geom.isCollapsed(track));
                }
                // Reordering is gone — tracks are derived from config order.
            }
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
                    activeCreationKind, geom.tracks().get(ti).channel(),
                    geom.xToTime(mx), mx);
                onCursor.accept(Cursor.CROSSHAIR);
                onRedraw.run();
            }
            return;
        }

        if (e.isAltDown() || e.isMiddleButtonDown()) {
            drag = new ClipDragState.PanView(geom.xToTime(mx), session.viewStart.get());
            onCursor.accept(Cursor.CLOSED_HAND);
            return;
        }

        // Rubber band selection on empty space
        if (!e.isShiftDown() && !e.isShortcutDown()) session.clearSelection();
        drag = new ClipDragState.RubberBand(mx, my, mx, my);
        onCursor.accept(Cursor.CROSSHAIR);
        onRedraw.run();
    }

    public void onDrag(MouseEvent e) {
        var geom = geometrySupplier.get();
        double mx = e.getX(), my = e.getY();
        snapGuideTime = Double.NaN;

        switch (drag) {
            case ClipDragState.Move m -> onMove(geom, m, mx, my);
            case ClipDragState.ResizeLeft rl -> onResizeLeft(geom, rl, mx);
            case ClipDragState.ResizeRight rr -> onResizeRight(geom, rr, mx);
            case ClipDragState.Amplitude a -> onAmplitude(geom, a, my);
            case ClipDragState.SplinePoint sp -> onSplinePoint(geom, sp, mx, my);
            case ClipDragState.PanView p -> onPan(geom, p, mx);
            case ClipDragState.CreateClip c ->
                drag = new ClipDragState.CreateClip(c.kind(), c.channel(), c.startTimeMicros(), mx);
            case ClipDragState.RubberBand rb ->
                drag = new ClipDragState.RubberBand(rb.startX(), rb.startY(), mx, my);
            case ClipDragState.Idle ignored -> { /* nothing */ }
        }
        onRedraw.run();
    }

    public void onRelease(MouseEvent e) {
        snapGuideTime = Double.NaN;
        var geom = geometrySupplier.get();

        switch (drag) {
            case ClipDragState.CreateClip c -> finishCreate(c, geom, e.getX());
            case ClipDragState.RubberBand rb -> finishRubberBand(rb, geom, e);
            default -> { /* nothing */ }
        }

        drag = ClipDragState.IDLE;
        onCursor.accept(Cursor.DEFAULT);
        onRedraw.run();
    }

    public void onMouseMoved(MouseEvent e) {
        var geom = geometrySupplier.get();
        double mx = e.getX(), my = e.getY();
        if (mx < geom.labelWidth()) {
            onCursor.accept(Cursor.DEFAULT);
            return;
        }
        var hit = hitTest(geom, mx, my);
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
        session.zoomViewAround(geom.xToTime(e.getX()), e.getDeltaY() > 0 ? 0.8 : 1.25);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Drag start / update / finish helpers
    // ══════════════════════════════════════════════════════════════════════════

    private void startClipDrag(MouseEvent e, TimelineGeometry geom, ClipHit hit) {
        var clip = session.findClip(hit.clipId);
        if (clip == null) return;

        // Selection updates
        if (e.isShortcutDown())        session.toggleSelection(hit.clipId);
        else if (e.isShiftDown())      session.addToSelection(hit.clipId);
        else if (!session.isSelected(hit.clipId)) session.selectOnly(hit.clipId);
        else                           session.primarySelectedClipId.set(hit.clipId);

        if (e.isAltDown() && hit.zone == HitZone.BODY) {
            drag = new ClipDragState.Amplitude(hit.clipId, e.getY(), hit.trackIndex, clip.amplitude());
            onCursor.accept(Cursor.V_RESIZE);
            return;
        }
        switch (hit.zone) {
            case LEFT_EDGE -> {
                drag = new ClipDragState.ResizeLeft(hit.clipId);
                onCursor.accept(Cursor.H_RESIZE);
            }
            case RIGHT_EDGE -> {
                drag = new ClipDragState.ResizeRight(hit.clipId);
                onCursor.accept(Cursor.H_RESIZE);
            }
            case SPLINE_POINT -> {
                drag = new ClipDragState.SplinePoint(hit.clipId, hit.splinePointIndex);
                onCursor.accept(Cursor.CROSSHAIR);
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

        // Cross-track: when dragging into a different track, change channel.
        int ti = geom.trackAtY(my);
        if (ti >= 0 && ti != m.originTrackIndex()) {
            var newChannel = geom.tracks().get(ti).channel();
            session.changeClipChannel(m.primaryClipId(), newChannel);
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
        double displayMax = TimelineRenderer.channelHardwareMax(session, geom.tracks().get(a.trackIndex()).channel());
        double deltaAmp = (deltaY / (h * 0.42)) * displayMax;
        session.setClipAmplitude(a.clipId(), a.originalAmplitude() + deltaAmp);
    }

    private void onSplinePoint(TimelineGeometry geom, ClipDragState.SplinePoint sp, double mx, double my) {
        var clip = session.findClip(sp.clipId());
        if (clip == null || !(clip.shape() instanceof ClipShape.Spline)) return;
        int ti = geom.trackAtY(my);
        if (ti < 0) ti = 0;
        double t = (geom.xToTime(mx) - clip.startTime()) / clip.duration();
        t = Math.max(0, Math.min(1, t));
        double displayMax = TimelineRenderer.channelDisplayMax(session, clip.channel());
        double v = clip.amplitude() != 0 ? geom.yToValue(ti, my, displayMax) / clip.amplitude() : 0;
        session.updateSplinePoint(sp.clipId(), sp.pointIndex(), new ClipShape.Spline.Point(t, v));
    }

    private void onPan(TimelineGeometry geom, ClipDragState.PanView p, double mx) {
        double span = session.viewEnd.get() - session.viewStart.get();
        double mouseFraction = (mx - geom.plotLeft()) / geom.plotWidth();
        double mouseTimeInOriginalView = p.viewStartAnchorMicros() + mouseFraction * span;
        double offset = p.anchorTimeMicros() - mouseTimeInOriginalView;
        session.viewStart.set(p.viewStartAnchorMicros() + offset);
        session.viewEnd.set(p.viewStartAnchorMicros() + span + offset);
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
            session.addClip(session.createClipCentred(c.kind(), c.channel(), start, duration));
        }
    }

    private void finishRubberBand(ClipDragState.RubberBand rb, TimelineGeometry geom, MouseEvent e) {
        double tStart = geom.xToTime(Math.min(rb.startX(), rb.endX()));
        double tEnd = geom.xToTime(Math.max(rb.startX(), rb.endX()));
        double yMin = Math.min(rb.startY(), rb.endY());
        double yMax = Math.max(rb.startY(), rb.endY());
        var channels = new LinkedHashSet<SequenceChannel>();
        for (int i = 0; i < geom.tracks().size(); i++) {
            double top = geom.trackTop(i);
            double h = geom.trackHeight(i);
            if (top + h > yMin && top < yMax) channels.add(geom.tracks().get(i).channel());
        }
        boolean addToExisting = e.isShiftDown() || e.isShortcutDown();
        session.selectClipsInRegion(tStart, tEnd, channels, addToExisting);
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
        double displayMax = TimelineRenderer.channelDisplayMax(session, track.channel());

        var channelClips = session.clipsOnChannel(track.channel());
        for (int i = channelClips.size() - 1; i >= 0; i--) {
            var clip = channelClips.get(i);
            double x1 = geom.timeToX(clip.startTime());
            double x2 = geom.timeToX(clip.endTime());
            if (mx < x1 - EDGE_HIT_WIDTH || mx > x2 + EDGE_HIT_WIDTH) continue;
            if (my < top || my > top + h) continue;

            // Spline points (only when selected)
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

    private boolean inCollapseButton(TimelineGeometry geom, EditorTrack track, int trackIndex, double mx) {
        return mx >= COLLAPSE_HIT_X && mx <= COLLAPSE_HIT_X + COLLAPSE_HIT_W;
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
                var toggle = new MenuItem(geom.isCollapsed(track) ? "Expand Track" : "Collapse Track");
                toggle.setOnAction(ev -> session.setChannelCollapsed(track.channel(), !geom.isCollapsed(track)));
                menu.getItems().add(toggle);

                var collapseAll = new MenuItem("Collapse All");
                collapseAll.setOnAction(ev -> {
                    for (var t : geom.tracks()) session.setChannelCollapsed(t.channel(), true);
                });
                var expandAll = new MenuItem("Expand All");
                expandAll.setOnAction(ev -> {
                    for (var t : geom.tracks()) session.setChannelCollapsed(t.channel(), false);
                });
                menu.getItems().addAll(new SeparatorMenuItem(), collapseAll, expandAll);
            }
            return menu;
        }

        var hit = hitTest(geom, mx, my);
        if (hit != null) {
            if (!session.isSelected(hit.clipId)) session.selectOnly(hit.clipId);
            var clip = session.findClip(hit.clipId);
            if (clip == null) return menu;

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
            return menu;
        }

        // Right-clicked on empty space within a track
        int ti = geom.trackAtY(my);
        if (ti >= 0 && !geom.isCollapsed(geom.tracks().get(ti))) {
            double time = geom.xToTime(mx);
            var ch = geom.tracks().get(ti).channel();
            var addMenu = new Menu("Add Clip");
            for (var kind : ClipKind.values()) {
                var item = new MenuItem(kind.displayName());
                item.setOnAction(ev -> session.addClip(session.createDefaultClip(kind, ch, time)));
                addMenu.getItems().add(item);
            }
            menu.getItems().add(addMenu);
        }
        var zoomFit = new MenuItem("Zoom to Fit");
        zoomFit.setOnAction(ev -> session.fitView());
        menu.getItems().add(zoomFit);
        return menu;
    }

    private void addSplinePointAt(SignalClip clip, TimelineGeometry geom, double mx, double my) {
        double t = (geom.xToTime(mx) - clip.startTime()) / clip.duration();
        t = Math.max(0, Math.min(1, t));
        int ti = geom.trackAtY(my);
        double displayMax = TimelineRenderer.channelDisplayMax(session, clip.channel());
        double v = ti >= 0 && clip.amplitude() != 0
            ? geom.yToValue(ti, my, displayMax) / clip.amplitude()
            : 0.5;
        session.addSplinePoint(clip.id(), new ClipShape.Spline.Point(t, v));
    }
}
