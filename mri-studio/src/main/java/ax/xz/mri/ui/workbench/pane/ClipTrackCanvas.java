package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.hardware.HardwareLimits;
import ax.xz.mri.model.sequence.ClipShape;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.FieldDefinition;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.ui.viewmodel.ViewportViewModel;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.ui.framework.ResizableCanvas;
import ax.xz.mri.ui.viewmodel.EditorTrack;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import javafx.animation.AnimationTimer;
import javafx.scene.Cursor;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static ax.xz.mri.ui.theme.StudioTheme.BG;
import static ax.xz.mri.ui.theme.StudioTheme.BG2;
import static ax.xz.mri.ui.theme.StudioTheme.GR;
import static ax.xz.mri.ui.theme.StudioTheme.TX2;
import static ax.xz.mri.ui.theme.StudioTheme.UI_7;
import static ax.xz.mri.ui.theme.StudioTheme.UI_BOLD_7;

/**
 * DAW-style multi-track canvas for editing signal clips in a pulse sequence.
 * Supports dynamic tracks, multi-selection, rubber-band select, cross-track drag,
 * snap-to-edge, clip splitting, and waveform caching for performance.
 */
public class ClipTrackCanvas extends ResizableCanvas {
    private static final double LABEL_WIDTH = 52;
    private static final double PAD_R = 6;
    private static final double PAD_B = 16;
    private static final double CLIP_RADIUS = 4;
    private static final double EDGE_HIT_WIDTH = 6;
    private static final double SPLINE_POINT_RADIUS = 4;
    private static final double COLLAPSED_HEIGHT = 14;

    private static final Color B1_COLOUR = Color.web("#1976d2");
    private static final Color GX_COLOUR = Color.web("#2e7d32");
    private static final Color GZ_COLOUR = Color.web("#7b1fa2");
    private static final Color GATE_COLOUR = Color.web("#ff6f00");

    // Lane weights for expanded tracks (by channel type)
    private static final double WEIGHT_B1 = 2.0;
    private static final double WEIGHT_G = 1.5;
    private static final double WEIGHT_GATE = 0.8;

    // Drag modes
    private static final int DRAG_NONE = 0;
    private static final int DRAG_CLIP_MOVE = 1;
    private static final int DRAG_CLIP_RESIZE_LEFT = 2;
    private static final int DRAG_CLIP_RESIZE_RIGHT = 3;
    private static final int DRAG_CLIP_AMPLITUDE = 4;
    private static final int DRAG_SPLINE_POINT = 5;
    private static final int DRAG_PAN_VIEW = 6;
    private static final int DRAG_CREATE_CLIP = 7;
    private static final int DRAG_RUBBER_BAND = 8;
    private static final int DRAG_REORDER_TRACK = 9;

    private static final double COLLAPSE_BTN_SIZE = 10;
    private static final double COLLAPSE_BTN_X = 3;

    private final SequenceEditSession editSession;
    private final WaveformCache waveformCache = new WaveformCache();
    private final AnimationTimer timer;
    private boolean dirty = true;
    private ContextMenu activeContextMenu;

    // Drag state
    private int dragMode = DRAG_NONE;
    private String dragClipId;
    private double dragAnchorTime;
    private double dragClipOriginalAmplitude;
    private double dragAnchorY;
    private double dragViewStartAnchor;
    private int dragSplinePointIndex;
    private int dragOriginTrackIndex = -1;
    private boolean dragIsMultiMove; // moving multiple selected clips
    private double dragMultiAnchorTime; // time at drag start for delta calculation

    // Rubber band state
    private double rubberStartX, rubberStartY, rubberEndX, rubberEndY;

    // Clip creation via drag
    private double createClipStartTime;
    private double createClipCurrentX; // for preview
    private SequenceChannel createClipChannel;

    // Track reorder
    private int reorderSourceTrack = -1;
    private double reorderCurrentY;

    // Active tool
    private ClipShape activeCreationShape = null;

    // Snap guide (drawn during drag)
    private double snapGuideTime = Double.NaN;

    // Global viewport for cursor display
    private ViewportViewModel viewport;

    public ClipTrackCanvas(SequenceEditSession editSession) {
        this.editSession = editSession;
        setOnResized(this::scheduleRedraw);

        editSession.revision.addListener((obs, o, n) -> { waveformCache.clear(); scheduleRedraw(); });
        editSession.viewStart.addListener((obs, o, n) -> scheduleRedraw());
        editSession.viewEnd.addListener((obs, o, n) -> scheduleRedraw());
        editSession.selectedClipIds.addListener((javafx.collections.SetChangeListener<String>) c -> scheduleRedraw());
        editSession.tracks.addListener((javafx.collections.ListChangeListener<EditorTrack>) c -> scheduleRedraw());

        setOnMousePressed(this::onMousePressed);
        setOnMouseDragged(this::onMouseDragged);
        setOnMouseReleased(this::onMouseReleased);
        setOnMouseMoved(this::onMouseMoved);
        setOnScroll(this::onScroll);
        setOnContextMenuRequested(e -> {
            showContextMenu(e.getX(), e.getY(), e.getScreenX(), e.getScreenY());
            e.consume();
        });

        // Fix context menu dismissal: hide on primary click
        addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (activeContextMenu != null && activeContextMenu.isShowing() && event.isPrimaryButtonDown()) {
                activeContextMenu.hide();
                activeContextMenu = null;
            }
        });

        timer = new AnimationTimer() {
            @Override public void handle(long now) {
                if (dirty) { dirty = false; paint(); }
            }
        };
        timer.start();
    }

    public void setActiveCreationShape(ClipShape shape) { this.activeCreationShape = shape; }
    public void setViewport(ViewportViewModel vp) {
        this.viewport = vp;
        vp.tC.addListener((obs, o, n) -> scheduleRedraw());
    }
    public void dispose() { timer.stop(); }
    private void scheduleRedraw() { dirty = true; }

    // ==================== Dynamic track geometry ====================

    private List<EditorTrack> currentTracks() { return editSession.tracks; }

    private double trackWeight(EditorTrack track) {
        if (track.collapsed()) return 0; // collapsed tracks use fixed height, not weight
        if (track.channel().isRfGate()) return WEIGHT_GATE;
        var field = editSession.fieldForChannel(track.channel());
        if (field == null) return WEIGHT_G;
        return switch (field.kind()) {
            case QUADRATURE -> WEIGHT_B1;   // RF-like (2-channel I/Q)
            case REAL -> WEIGHT_G;          // gradient-like (1-channel)
            case STATIC -> WEIGHT_G;        // unreachable — STATIC has no channels
        };
    }

    private double plotLeft()  { return LABEL_WIDTH; }
    private double plotWidth() { return Math.max(1, getWidth() - LABEL_WIDTH - PAD_R); }
    private double plotHeight(){ return Math.max(1, getHeight() - PAD_B); }

    /** Total weight of all expanded tracks. */
    private double totalExpandedWeight() {
        double w = 0;
        for (var t : currentTracks()) if (!t.collapsed()) w += trackWeight(t);
        return Math.max(0.1, w);
    }

    /** Total fixed height consumed by collapsed tracks. */
    private double totalCollapsedHeight() {
        double h = 0;
        for (var t : currentTracks()) if (t.collapsed()) h += COLLAPSED_HEIGHT;
        return h;
    }

    /** Available height for expanded tracks after subtracting collapsed ones. */
    private double expandedAreaHeight() {
        return Math.max(1, plotHeight() - totalCollapsedHeight());
    }

    private double trackTop(int trackIndex) {
        var tracks = currentTracks();
        double y = 0;
        for (int i = 0; i < trackIndex && i < tracks.size(); i++) {
            y += trackHeight(i);
        }
        return y;
    }

    private double trackHeight(int trackIndex) {
        var track = currentTracks().get(trackIndex);
        if (track.collapsed()) return COLLAPSED_HEIGHT;
        return expandedAreaHeight() * trackWeight(track) / totalExpandedWeight();
    }

    private int trackAtY(double y) {
        var tracks = currentTracks();
        double cumY = 0;
        for (int i = 0; i < tracks.size(); i++) {
            double h = trackHeight(i);
            if (y >= cumY && y < cumY + h) return i;
            cumY += h;
        }
        return -1;
    }

    // ==================== Coordinate mapping ====================

    private double timeToX(double timeMicros) {
        double vS = editSession.viewStart.get();
        double span = Math.max(1e-9, editSession.viewEnd.get() - vS);
        return plotLeft() + (timeMicros - vS) / span * plotWidth();
    }

    private double xToTime(double x) {
        double vS = editSession.viewStart.get();
        double span = Math.max(1e-9, editSession.viewEnd.get() - vS);
        return vS + (x - plotLeft()) / plotWidth() * span;
    }

    private double valueToY(int trackIndex, double value) {
        double top = trackTop(trackIndex);
        double h = trackHeight(trackIndex);
        double max = channelMax(currentTracks().get(trackIndex).channel());
        double mid = top + h / 2;
        double normalised = max > 0 ? Math.max(-1, Math.min(1, value / max)) : 0;
        return mid - normalised * (h * 0.42);
    }

    private double yToValue(int trackIndex, double y) {
        double top = trackTop(trackIndex);
        double h = trackHeight(trackIndex);
        double max = channelMax(currentTracks().get(trackIndex).channel());
        double mid = top + h / 2;
        return -(y - mid) / (h * 0.42) * max;
    }

    /** Y-axis half-span for a track — the displayed range is ±channelMax, with a 15% headroom. */
    private double channelMax(SequenceChannel ch) {
        if (ch.isRfGate()) return 1.15;
        var field = editSession.fieldForChannel(ch);
        if (field != null) {
            double m = Math.max(Math.abs(field.minAmplitude()), Math.abs(field.maxAmplitude()));
            if (m > 0) return m * 1.15;
        }
        // Config-less sequence — fall back to a generous unit range.
        return 1.15;
    }

    /** Hardware limit line shown as a red dashed overlay. Mirrors channelMax / 1.15. */
    private double channelHardwareMax(SequenceChannel ch) {
        if (ch.isRfGate()) return 1.0;
        var field = editSession.fieldForChannel(ch);
        if (field != null) {
            double m = Math.max(Math.abs(field.minAmplitude()), Math.abs(field.maxAmplitude()));
            if (m > 0) return m;
        }
        return 1.0;
    }

    private Color channelColour(SequenceChannel ch) {
        if (ch.isRfGate()) return GATE_COLOUR;
        var field = editSession.fieldForChannel(ch);
        if (field == null) return GX_COLOUR;
        return switch (field.kind()) {
            case QUADRATURE -> B1_COLOUR;
            case REAL -> fieldPaletteColour(field.name());
            case STATIC -> GX_COLOUR;
        };
    }

    /** Deterministic colour for a REAL field, cycled by field name. */
    private static Color fieldPaletteColour(String fieldName) {
        int h = Math.abs(fieldName.hashCode()) % REAL_PALETTE.length;
        return REAL_PALETTE[h];
    }

    private static final Color[] REAL_PALETTE = {
        Color.web("#2e7d32"), // GX-like green
        Color.web("#7b1fa2"), // GZ-like purple
        Color.web("#c2185b"), // magenta
        Color.web("#00897b"), // teal
        Color.web("#ef6c00"), // deep orange
    };

    // ==================== Hit testing ====================

    private record ClipHit(String clipId, HitZone zone, int splinePointIndex, int trackIndex) {}
    private enum HitZone { BODY, LEFT_EDGE, RIGHT_EDGE, SPLINE_POINT }

    private ClipHit hitTest(double mx, double my) {
        int ti = trackAtY(my);
        if (ti < 0) return null;
        var track = currentTracks().get(ti);
        if (track.collapsed()) return null;

        var channelClips = editSession.clipsOnChannel(track.channel());
        for (int i = channelClips.size() - 1; i >= 0; i--) {
            var clip = channelClips.get(i);
            double x1 = timeToX(clip.startTime());
            double x2 = timeToX(clip.endTime());
            double tTop = trackTop(ti);
            double tH = trackHeight(ti);

            if (mx < x1 - EDGE_HIT_WIDTH || mx > x2 + EDGE_HIT_WIDTH) continue;
            if (my < tTop || my > tTop + tH) continue;

            // Spline points (only when selected)
            if (clip.shape() == ClipShape.SPLINE && editSession.isSelected(clip.id())) {
                for (int sp = 0; sp < clip.splinePoints().size(); sp++) {
                    var pt = clip.splinePoints().get(sp);
                    double ptX = timeToX(clip.startTime() + pt.t() * clip.duration());
                    double ptY = valueToY(ti, pt.value() * clip.amplitude());
                    if (Math.abs(mx - ptX) <= SPLINE_POINT_RADIUS + 2 &&
                        Math.abs(my - ptY) <= SPLINE_POINT_RADIUS + 2) {
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

    // ==================== Interaction ====================

    private void onMousePressed(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) return;
        double mx = e.getX(), my = e.getY();

        // Track label area: collapse button or start track reorder
        if (mx < LABEL_WIDTH) {
            int ti = trackAtY(my);
            if (ti >= 0) {
                var track = currentTracks().get(ti);
                double tTop = trackTop(ti);
                double btnY = tTop + trackHeight(ti) / 2 - COLLAPSE_BTN_SIZE / 2;
                // Collapse/expand button hit
                if (mx >= COLLAPSE_BTN_X && mx <= COLLAPSE_BTN_X + COLLAPSE_BTN_SIZE &&
                    my >= btnY && my <= btnY + COLLAPSE_BTN_SIZE) {
                    editSession.setTrackCollapsed(track.id(), !track.collapsed());
                    return;
                }
                // Start track reorder drag
                dragMode = DRAG_REORDER_TRACK;
                reorderSourceTrack = ti;
                reorderCurrentY = my;
                setCursor(Cursor.V_RESIZE);
            }
            return;
        }

        var hit = hitTest(mx, my);
        if (hit != null) {
            var clip = editSession.findClip(hit.clipId);
            if (clip == null) return;

            // Selection with modifiers
            if (e.isShortcutDown()) {
                editSession.toggleSelection(hit.clipId);
            } else if (e.isShiftDown()) {
                editSession.addToSelection(hit.clipId);
            } else if (!editSession.isSelected(hit.clipId)) {
                editSession.selectOnly(hit.clipId);
            } else {
                // Already selected — set as primary for inspector
                editSession.primarySelectedClipId.set(hit.clipId);
            }

            if (e.isAltDown() && hit.zone == HitZone.BODY) {
                dragMode = DRAG_CLIP_AMPLITUDE;
                dragClipId = hit.clipId;
                dragAnchorY = my;
                dragClipOriginalAmplitude = clip.amplitude();
                setCursor(Cursor.V_RESIZE);
            } else {
                switch (hit.zone) {
                    case LEFT_EDGE -> {
                        dragMode = DRAG_CLIP_RESIZE_LEFT;
                        dragClipId = hit.clipId;
                        setCursor(Cursor.H_RESIZE);
                    }
                    case RIGHT_EDGE -> {
                        dragMode = DRAG_CLIP_RESIZE_RIGHT;
                        dragClipId = hit.clipId;
                        setCursor(Cursor.H_RESIZE);
                    }
                    case SPLINE_POINT -> {
                        dragMode = DRAG_SPLINE_POINT;
                        dragClipId = hit.clipId;
                        dragSplinePointIndex = hit.splinePointIndex;
                        setCursor(Cursor.CROSSHAIR);
                    }
                    case BODY -> {
                        dragMode = DRAG_CLIP_MOVE;
                        dragClipId = hit.clipId;
                        dragOriginTrackIndex = hit.trackIndex;
                        dragIsMultiMove = editSession.selectedClipIds.size() > 1 && editSession.isSelected(hit.clipId);
                        dragMultiAnchorTime = xToTime(mx);
                        dragAnchorTime = xToTime(mx) - clip.startTime();
                        setCursor(Cursor.CLOSED_HAND);
                    }
                }
            }
        } else if (activeCreationShape != null) {
            int ti = trackAtY(my);
            if (ti >= 0 && !currentTracks().get(ti).collapsed()) {
                dragMode = DRAG_CREATE_CLIP;
                createClipStartTime = xToTime(mx);
                createClipChannel = currentTracks().get(ti).channel();
                setCursor(Cursor.CROSSHAIR);
            }
        } else if (e.isAltDown() || e.isMiddleButtonDown()) {
            // Alt+click or middle = pan
            dragMode = DRAG_PAN_VIEW;
            dragAnchorTime = xToTime(mx);
            dragViewStartAnchor = editSession.viewStart.get();
            setCursor(Cursor.CLOSED_HAND);
        } else {
            // Rubber band selection
            if (!e.isShiftDown() && !e.isShortcutDown()) {
                editSession.clearSelection();
            }
            dragMode = DRAG_RUBBER_BAND;
            rubberStartX = mx; rubberStartY = my;
            rubberEndX = mx; rubberEndY = my;
            setCursor(Cursor.CROSSHAIR);
        }
    }

    private void onMouseDragged(MouseEvent e) {
        double mx = e.getX(), my = e.getY();
        snapGuideTime = Double.NaN;

        switch (dragMode) {
            case DRAG_CLIP_MOVE -> {
                if (dragIsMultiMove) {
                    double currentTime = xToTime(mx);
                    double delta = currentTime - dragMultiAnchorTime;
                    if (editSession.snapEnabled.get()) {
                        // Snap the primary clip's new start
                        var primary = editSession.findClip(dragClipId);
                        if (primary != null) {
                            double rawStart = primary.startTime() + delta;
                            double snapped = editSession.snapTime(rawStart);
                            delta += snapped - rawStart;
                            if (snapped != rawStart) snapGuideTime = snapped;
                        }
                    }
                    editSession.moveSelectedClips(delta);
                    dragMultiAnchorTime = currentTime;
                } else {
                    double newStart = xToTime(mx) - dragAnchorTime;
                    newStart = Math.max(0, newStart);
                    if (editSession.snapEnabled.get()) {
                        double snapped = editSession.snapTime(newStart);
                        if (snapped != newStart) snapGuideTime = snapped;
                        newStart = snapped;
                    }
                    editSession.moveClip(dragClipId, newStart);
                }
                // Cross-track: check if we've moved to a different track
                int currentTrack = trackAtY(my);
                if (currentTrack >= 0 && currentTrack != dragOriginTrackIndex) {
                    var newChannel = currentTracks().get(currentTrack).channel();
                    editSession.changeClipChannel(dragClipId, newChannel);
                    dragOriginTrackIndex = currentTrack;
                }
            }
            case DRAG_CLIP_RESIZE_LEFT -> {
                double newStart = xToTime(mx);
                if (editSession.snapEnabled.get()) {
                    double snapped = editSession.snapTime(newStart);
                    if (snapped != newStart) snapGuideTime = snapped;
                    newStart = snapped;
                }
                editSession.resizeClipLeft(dragClipId, newStart);
            }
            case DRAG_CLIP_RESIZE_RIGHT -> {
                double newEnd = xToTime(mx);
                if (editSession.snapEnabled.get()) {
                    double snapped = editSession.snapTime(newEnd);
                    if (snapped != newEnd) snapGuideTime = snapped;
                    newEnd = snapped;
                }
                var clip = editSession.findClip(dragClipId);
                if (clip != null) {
                    editSession.resizeClip(dragClipId, Math.max(editSession.dt.get(), newEnd - clip.startTime()));
                }
            }
            case DRAG_CLIP_AMPLITUDE -> {
                int ti = trackAtY(dragAnchorY);
                if (ti >= 0) {
                    double deltaY = dragAnchorY - my;
                    double lH = trackHeight(ti);
                    double maxVal = channelHardwareMax(currentTracks().get(ti).channel());
                    double deltaAmp = (deltaY / (lH * 0.42)) * maxVal;
                    editSession.setClipAmplitude(dragClipId, dragClipOriginalAmplitude + deltaAmp);
                }
            }
            case DRAG_SPLINE_POINT -> {
                var clip = editSession.findClip(dragClipId);
                if (clip != null && dragSplinePointIndex >= 0) {
                    int ti = trackAtY(my);
                    if (ti < 0) ti = 0;
                    double t = (xToTime(mx) - clip.startTime()) / clip.duration();
                    t = Math.max(0, Math.min(1, t));
                    double value = clip.amplitude() != 0 ? yToValue(ti, my) / clip.amplitude() : 0;
                    editSession.updateSplinePoint(dragClipId, dragSplinePointIndex,
                        new SignalClip.SplinePoint(t, value));
                }
            }
            case DRAG_PAN_VIEW -> {
                double span = editSession.viewEnd.get() - editSession.viewStart.get();
                double mouseTimeFraction = (mx - plotLeft()) / plotWidth();
                double mouseTimeInOriginalView = dragViewStartAnchor + mouseTimeFraction * span;
                double offset = dragAnchorTime - mouseTimeInOriginalView;
                editSession.viewStart.set(dragViewStartAnchor + offset);
                editSession.viewEnd.set(dragViewStartAnchor + span + offset);
            }
            case DRAG_RUBBER_BAND -> {
                rubberEndX = mx;
                rubberEndY = my;
                scheduleRedraw();
            }
            case DRAG_CREATE_CLIP -> {
                createClipCurrentX = mx;
                scheduleRedraw();
            }
            case DRAG_REORDER_TRACK -> {
                reorderCurrentY = my;
                int target = trackAtY(my);
                if (target >= 0 && target != reorderSourceTrack) {
                    editSession.reorderTrack(reorderSourceTrack, target);
                    reorderSourceTrack = target;
                }
                scheduleRedraw();
            }
        }
    }

    private void onMouseReleased(MouseEvent e) {
        double mx = e.getX(), my = e.getY();
        snapGuideTime = Double.NaN;

        if (dragMode == DRAG_CREATE_CLIP && activeCreationShape != null) {
            double endTime = xToTime(mx);
            double start = Math.min(createClipStartTime, endTime);
            double end = Math.max(createClipStartTime, endTime);
            if (editSession.snapEnabled.get()) {
                start = editSession.snapTime(start);
                end = editSession.snapTime(end);
            }
            double duration = end - start;
            if (duration >= editSession.dt.get()) {
                var clip = editSession.createClipCentred(activeCreationShape, createClipChannel, start, duration);
                editSession.addClip(clip);
            }
        } else if (dragMode == DRAG_RUBBER_BAND) {
            double tStart = xToTime(Math.min(rubberStartX, rubberEndX));
            double tEnd = xToTime(Math.max(rubberStartX, rubberEndX));
            double yMin = Math.min(rubberStartY, rubberEndY);
            double yMax = Math.max(rubberStartY, rubberEndY);
            // Collect channels from tracks that overlap the Y range
            var channels = new LinkedHashSet<SequenceChannel>();
            var tracks = currentTracks();
            for (int i = 0; i < tracks.size(); i++) {
                double tTop = trackTop(i);
                double tH = trackHeight(i);
                if (tTop + tH > yMin && tTop < yMax) {
                    channels.add(tracks.get(i).channel());
                }
            }
            boolean addToExisting = e.isShiftDown() || e.isShortcutDown();
            editSession.selectClipsInRegion(tStart, tEnd, channels, addToExisting);
        }

        dragMode = DRAG_NONE;
        setCursor(Cursor.DEFAULT);
        scheduleRedraw();
    }

    private void onMouseMoved(MouseEvent e) {
        double mx = e.getX(), my = e.getY();
        if (mx < LABEL_WIDTH) {
            setCursor(Cursor.DEFAULT);
            return;
        }
        var hit = hitTest(mx, my);
        if (hit != null) {
            setCursor(switch (hit.zone) {
                case LEFT_EDGE, RIGHT_EDGE -> Cursor.H_RESIZE;
                case SPLINE_POINT -> Cursor.CROSSHAIR;
                case BODY -> e.isAltDown() ? Cursor.V_RESIZE : Cursor.HAND;
            });
        } else if (activeCreationShape != null) {
            setCursor(Cursor.CROSSHAIR);
        } else {
            setCursor(Cursor.DEFAULT);
        }
    }

    private void onScroll(ScrollEvent e) {
        editSession.zoomViewAround(xToTime(e.getX()), e.getDeltaY() > 0 ? 0.8 : 1.25);
    }

    // ==================== Context menu ====================

    private void showContextMenu(double mx, double my, double screenX, double screenY) {
        if (activeContextMenu != null) { activeContextMenu.hide(); activeContextMenu = null; }
        var menu = new ContextMenu();
        menu.setAutoHide(true);

        if (mx < LABEL_WIDTH) {
            // Track label context menu
            int ti = trackAtY(my);
            if (ti >= 0) {
                var track = currentTracks().get(ti);
                var toggleCollapse = new MenuItem(track.collapsed() ? "Expand Track" : "Collapse Track");
                toggleCollapse.setOnAction(e -> editSession.setTrackCollapsed(track.id(), !track.collapsed()));
                menu.getItems().add(toggleCollapse);

                // Add track for each available channel in the active config
                var addMenu = new Menu("Add Track");
                var config = editSession.activeConfig.get();
                if (config != null) {
                    for (var field : config.fields()) {
                        int count = field.kind().channelCount();
                        for (int sub = 0; sub < count; sub++) {
                            var channel = SequenceChannel.ofField(field.name(), sub);
                            String label = EditorTrack.labelFor(field.name(), field.kind(), sub);
                            var item = new MenuItem(label);
                            item.setOnAction(e -> editSession.addTrack(channel, label));
                            addMenu.getItems().add(item);
                        }
                    }
                }
                var gateItem = new MenuItem("RF Gate");
                gateItem.setOnAction(e -> editSession.addTrack(SequenceChannel.RF_GATE, "RF Gate"));
                addMenu.getItems().add(gateItem);
                menu.getItems().addAll(addMenu);

                var removeItem = new MenuItem("Remove Track");
                long count = currentTracks().stream().filter(t -> t.channel().equals(track.channel())).count();
                removeItem.setDisable(count <= 1);
                removeItem.setOnAction(e -> editSession.removeTrack(track.id()));
                menu.getItems().add(removeItem);
            }
        } else {
            var hit = hitTest(mx, my);
            if (hit != null) {
                if (!editSession.isSelected(hit.clipId)) editSession.selectOnly(hit.clipId);
                var clip = editSession.findClip(hit.clipId);
                if (clip != null) {
                    var deleteItem = new MenuItem("Delete");
                    deleteItem.setOnAction(e -> editSession.deleteSelectedClips());
                    var duplicateItem = new MenuItem("Duplicate");
                    duplicateItem.setOnAction(e -> editSession.duplicateSelectedClips());

                    var splitItem = new MenuItem("Split Clip Here");
                    splitItem.setOnAction(e -> editSession.splitClip(hit.clipId, xToTime(mx)));

                    var recentreItem = new MenuItem("Re-centre Media");
                    recentreItem.setOnAction(e -> editSession.recentreClip(hit.clipId));

                    var shapeMenu = new Menu("Change Shape");
                    for (var shape : ClipShape.values()) {
                        var item = new MenuItem(shape.displayName());
                        item.setDisable(shape == clip.shape());
                        item.setOnAction(e -> editSession.setClipShape(hit.clipId, shape));
                        shapeMenu.getItems().add(item);
                    }

                    menu.getItems().addAll(deleteItem, duplicateItem, splitItem, recentreItem,
                        new SeparatorMenuItem(), shapeMenu);

                    if (clip.shape() == ClipShape.SPLINE) {
                        menu.getItems().add(new SeparatorMenuItem());
                        var addPoint = new MenuItem("Add Spline Point Here");
                        addPoint.setOnAction(e -> {
                            double t = (xToTime(mx) - clip.startTime()) / clip.duration();
                            t = Math.max(0, Math.min(1, t));
                            int ti = trackAtY(my);
                            double value = ti >= 0 && clip.amplitude() != 0
                                ? yToValue(ti, my) / clip.amplitude() : 0.5;
                            editSession.addSplinePoint(hit.clipId, new SignalClip.SplinePoint(t, value));
                        });
                        menu.getItems().add(addPoint);
                        if (hit.zone == HitZone.SPLINE_POINT && hit.splinePointIndex >= 0) {
                            var removePoint = new MenuItem("Remove This Point");
                            removePoint.setOnAction(e -> editSession.removeSplinePoint(hit.clipId, hit.splinePointIndex));
                            menu.getItems().add(removePoint);
                        }
                    }
                }
            } else {
                int ti = trackAtY(my);
                if (ti >= 0 && !currentTracks().get(ti).collapsed()) {
                    double time = xToTime(mx);
                    var ch = currentTracks().get(ti).channel();
                    var addMenu = new Menu("Add Clip");
                    for (var shape : ClipShape.values()) {
                        var item = new MenuItem(shape.displayName());
                        item.setOnAction(e -> editSession.addClip(
                            editSession.createDefaultClip(shape, ch, time)));
                        addMenu.getItems().add(item);
                    }
                    menu.getItems().add(addMenu);
                }
                menu.getItems().add(new MenuItem("Zoom to Fit") {{ setOnAction(e -> editSession.fitView()); }});
            }
        }

        if (!menu.getItems().isEmpty()) {
            activeContextMenu = menu;
            menu.show(this, screenX, screenY);
        }
    }

    // ==================== Rendering ====================

    private void paint() {
        double w = getWidth();
        double h = getHeight();
        var g = getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        g.setFill(BG);
        g.fillRect(0, 0, w, h);

        double pL = plotLeft();
        double pW = plotWidth();
        double vS = editSession.viewStart.get();
        double vE = editSession.viewEnd.get();
        var tracks = currentTracks();

        for (int ti = 0; ti < tracks.size(); ti++) {
            var track = tracks.get(ti);
            double tTop = trackTop(ti);
            double tH = trackHeight(ti);

            // Background
            g.setFill(ti % 2 == 0 ? BG : BG2);
            g.fillRect(pL, tTop, pW, tH);

            // Lane border (strong line between tracks)
            g.setStroke(Color.color(0, 0, 0, 0.15));
            g.setLineWidth(1.0);
            g.strokeLine(0, tTop + tH, getWidth(), tTop + tH);

            // Label area background
            g.setFill(Color.color(0.94, 0.94, 0.96));
            g.fillRect(0, tTop, LABEL_WIDTH - 1, tH);
            // Label area right border
            g.setStroke(Color.color(0, 0, 0, 0.12));
            g.setLineWidth(0.5);
            g.strokeLine(LABEL_WIDTH - 0.5, tTop, LABEL_WIDTH - 0.5, tTop + tH);

            // Collapse/expand button (triangle)
            double btnY = tTop + tH / 2 - COLLAPSE_BTN_SIZE / 2;
            g.setFill(TX2);
            if (track.collapsed()) {
                // Right-pointing triangle ▶
                g.fillPolygon(
                    new double[]{COLLAPSE_BTN_X, COLLAPSE_BTN_X, COLLAPSE_BTN_X + COLLAPSE_BTN_SIZE},
                    new double[]{btnY, btnY + COLLAPSE_BTN_SIZE, btnY + COLLAPSE_BTN_SIZE / 2}, 3);
            } else {
                // Down-pointing triangle ▼
                g.fillPolygon(
                    new double[]{COLLAPSE_BTN_X, COLLAPSE_BTN_X + COLLAPSE_BTN_SIZE, COLLAPSE_BTN_X + COLLAPSE_BTN_SIZE / 2},
                    new double[]{btnY, btnY, btnY + COLLAPSE_BTN_SIZE}, 3);
            }

            // Label
            g.setFill(TX2);
            g.setFont(UI_BOLD_7);
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(track.label(), LABEL_WIDTH - 4, tTop + tH / 2 + 3);
            g.setTextAlign(TextAlignment.LEFT);

            if (track.collapsed()) {
                // Collapsed: draw thin coloured bars for clips
                drawCollapsedTrack(g, ti, tTop, tH);
                continue;
            }

            // Zero line
            if (!track.channel().isRfGate()) {
                double zeroY = valueToY(ti, 0);
                g.setStroke(Color.color(0, 0, 0, 0.08));
                g.setLineWidth(0.5);
                g.setLineDashes(4, 3);
                g.strokeLine(pL, zeroY, pL + pW, zeroY);
                g.setLineDashes();
            }

            // Hardware limit lines
            if (!track.channel().isRfGate()) {
                double hwMax = channelHardwareMax(track.channel());
                double yPos = valueToY(ti, hwMax);
                double yNeg = valueToY(ti, -hwMax);
                g.setStroke(Color.color(1, 0, 0, 0.2));
                g.setLineWidth(0.7);
                g.setLineDashes(5, 4);
                g.strokeLine(pL, yPos, pL + pW, yPos);
                g.strokeLine(pL, yNeg, pL + pW, yNeg);
                g.setLineDashes();

                g.setFill(Color.color(1, 0, 0, 0.35));
                g.setFont(UI_7);
                g.setTextAlign(TextAlignment.LEFT);
                g.fillText(formatAxisValue(hwMax, track.channel()), pL + 3, yPos - 2);
                g.setTextAlign(TextAlignment.LEFT);
            }

            // Clips
            drawTrackClips(g, ti);
        }

        // Creation preview (shows extent while dragging with a tool)
        if (dragMode == DRAG_CREATE_CLIP && createClipChannel != null) {
            int cti = -1;
            for (int i = 0; i < tracks.size(); i++) {
                if (tracks.get(i).channel() == createClipChannel) { cti = i; break; }
            }
            if (cti >= 0) {
                double startX = timeToX(createClipStartTime);
                double endX = createClipCurrentX;
                double cTop = trackTop(cti);
                double cH = trackHeight(cti);
                double px1 = Math.min(startX, endX);
                double px2 = Math.max(startX, endX);
                Color previewColour = channelColour(createClipChannel);
                g.setFill(previewColour.deriveColor(0, 1, 1, 0.12));
                g.fillRoundRect(px1, cTop + 2, px2 - px1, cH - 4, CLIP_RADIUS, CLIP_RADIUS);
                g.setStroke(previewColour.deriveColor(0, 1, 1, 0.6));
                g.setLineWidth(1);
                g.setLineDashes(4, 3);
                g.strokeRoundRect(px1, cTop + 2, px2 - px1, cH - 4, CLIP_RADIUS, CLIP_RADIUS);
                g.setLineDashes();
                // Time labels at edges
                g.setFill(previewColour);
                g.setFont(UI_7);
                g.setTextAlign(TextAlignment.CENTER);
                g.fillText(formatTime(xToTime(px1)), px1, cTop - 2);
                g.fillText(formatTime(xToTime(px2)), px2, cTop - 2);
            }
        }

        // Rubber band overlay
        if (dragMode == DRAG_RUBBER_BAND) {
            double rx = Math.min(rubberStartX, rubberEndX);
            double ry = Math.min(rubberStartY, rubberEndY);
            double rw = Math.abs(rubberEndX - rubberStartX);
            double rh = Math.abs(rubberEndY - rubberStartY);
            g.setFill(Color.color(0.1, 0.4, 0.8, 0.1));
            g.fillRect(rx, ry, rw, rh);
            g.setStroke(Color.color(0.1, 0.4, 0.8, 0.5));
            g.setLineWidth(1);
            g.strokeRect(rx, ry, rw, rh);
        }

        // Orange cursor line (synced with global viewport)
        if (viewport != null) {
            double cursorTime = viewport.tC.get();
            double cx = timeToX(cursorTime);
            if (cx >= plotLeft() && cx <= plotLeft() + plotWidth()) {
                g.setStroke(Color.web("#e06000"));
                g.setLineWidth(1.5);
                g.strokeLine(cx, 0, cx, plotHeight());
            }
        }

        // Snap guide
        if (!Double.isNaN(snapGuideTime)) {
            double sx = timeToX(snapGuideTime);
            g.setStroke(Color.color(0.0, 0.8, 0.3, 0.7));
            g.setLineWidth(1);
            g.setLineDashes(4, 3);
            g.strokeLine(sx, 0, sx, plotHeight());
            g.setLineDashes();
        }

        // Time axis
        drawTimeAxis(g, w, h);
    }

    private void drawCollapsedTrack(GraphicsContext g, int trackIndex, double tTop, double tH) {
        var ch = currentTracks().get(trackIndex).channel();
        Color colour = channelColour(ch);
        var channelClips = editSession.clipsOnChannel(ch);
        double vS = editSession.viewStart.get();
        double vE = editSession.viewEnd.get();

        for (var clip : channelClips) {
            if (clip.endTime() < vS || clip.startTime() > vE) continue;
            double x1 = timeToX(clip.startTime());
            double x2 = timeToX(clip.endTime());
            boolean sel = editSession.isSelected(clip.id());
            g.setFill(sel ? colour.deriveColor(0, 1, 1, 0.5) : colour.deriveColor(0, 1, 1, 0.25));
            g.fillRect(x1, tTop + 2, Math.max(1, x2 - x1), tH - 4);
        }
    }

    private void drawTrackClips(GraphicsContext g, int trackIndex) {
        var track = currentTracks().get(trackIndex);
        var channelClips = editSession.clipsOnChannel(track.channel());
        double tTop = trackTop(trackIndex);
        double tH = trackHeight(trackIndex);
        double vS = editSession.viewStart.get();
        double vE = editSession.viewEnd.get();

        for (var clip : channelClips) {
            if (clip.endTime() < vS || clip.startTime() > vE) continue; // viewport culling

            double x1 = timeToX(clip.startTime());
            double x2 = timeToX(clip.endTime());
            double clipW = Math.max(1, x2 - x1);

            Color baseColour = channelColour(track.channel());
            boolean selected = editSession.isSelected(clip.id());

            // Clip background
            g.setFill(selected ? baseColour.deriveColor(0, 1, 1, 0.18) : baseColour.deriveColor(0, 1, 1, 0.08));
            g.fillRoundRect(x1, tTop + 2, clipW, tH - 4, CLIP_RADIUS, CLIP_RADIUS);

            // Clip border
            g.setStroke(selected ? baseColour : baseColour.deriveColor(0, 1, 1, 0.5));
            g.setLineWidth(selected ? 1.5 : 0.8);
            g.strokeRoundRect(x1, tTop + 2, clipW, tH - 4, CLIP_RADIUS, CLIP_RADIUS);

            // Clip waveform (clipped to bounds)
            g.save();
            g.beginPath();
            g.rect(x1, tTop + 2, clipW, tH - 4);
            g.clip();
            drawClipWaveform(g, clip, trackIndex, x1, x2);
            g.restore();

            // Shape label
            if (clipW > 30) {
                g.setFill(baseColour.deriveColor(0, 1, 1, 0.7));
                g.setFont(UI_7);
                g.setTextAlign(TextAlignment.LEFT);
                g.fillText(clip.shape().displayName(), x1 + 3, tTop + 12);
            }

            // Spline control points (when selected)
            if (clip.shape() == ClipShape.SPLINE && selected) {
                for (int sp = 0; sp < clip.splinePoints().size(); sp++) {
                    var pt = clip.splinePoints().get(sp);
                    double ptX = timeToX(clip.startTime() + pt.t() * clip.duration());
                    double ptY = valueToY(trackIndex, pt.value() * clip.amplitude());
                    g.setFill(Color.WHITE);
                    g.fillOval(ptX - SPLINE_POINT_RADIUS, ptY - SPLINE_POINT_RADIUS,
                        SPLINE_POINT_RADIUS * 2, SPLINE_POINT_RADIUS * 2);
                    g.setStroke(baseColour);
                    g.setLineWidth(1.2);
                    g.strokeOval(ptX - SPLINE_POINT_RADIUS, ptY - SPLINE_POINT_RADIUS,
                        SPLINE_POINT_RADIUS * 2, SPLINE_POINT_RADIUS * 2);
                }
            }

            // Resize handles (when selected)
            if (selected) {
                g.setStroke(baseColour);
                g.setLineWidth(2);
                g.strokeLine(x1, tTop + 6, x1, tTop + tH - 6);
                g.strokeLine(x2, tTop + 6, x2, tTop + tH - 6);
            }
        }
    }

    private void drawClipWaveform(GraphicsContext g, SignalClip clip, int trackIndex, double x1, double x2) {
        double clipW = x2 - x1;
        int samples = (int) Math.max(2, Math.min(clipW, 300));
        Color colour = channelColour(currentTracks().get(trackIndex).channel());

        // Use cache
        double[] values = waveformCache.getOrCompute(clip, samples);

        g.setStroke(colour.deriveColor(0, 1, 1, 0.8));
        g.setLineWidth(1.2);
        g.beginPath();

        for (int i = 0; i <= samples; i++) {
            double x = x1 + ((double) i / samples) * clipW;
            double y = valueToY(trackIndex, values[i]);
            if (i == 0) g.moveTo(x, y); else g.lineTo(x, y);
        }
        g.stroke();
    }

    private void drawTimeAxis(GraphicsContext g, double w, double h) {
        double vS = editSession.viewStart.get();
        double vE = editSession.viewEnd.get();
        double span = vE - vS;
        double pL = plotLeft();
        double pW = plotWidth();
        double axisY = h - PAD_B;

        g.setStroke(GR);
        g.setLineWidth(0.5);
        g.strokeLine(pL, axisY, pL + pW, axisY);

        double rawStep = span / 8;
        double magnitude = Math.pow(10, Math.floor(Math.log10(Math.max(1e-9, rawStep))));
        double normalised = rawStep / magnitude;
        double tickStep;
        if (normalised <= 2) tickStep = 2 * magnitude;
        else if (normalised <= 5) tickStep = 5 * magnitude;
        else tickStep = 10 * magnitude;

        double firstTick = Math.ceil(vS / tickStep) * tickStep;
        g.setFill(TX2);
        g.setFont(UI_7);
        g.setTextAlign(TextAlignment.CENTER);

        for (double t = firstTick; t <= vE; t += tickStep) {
            double x = timeToX(t);
            g.strokeLine(x, axisY, x, axisY + 3);
            g.fillText(formatTime(t), x, axisY + 12);
        }
    }

    private String formatTime(double micros) {
        if (micros >= 1000) return String.format("%.1f ms", micros / 1000);
        return String.format("%.0f μs", micros);
    }

    /**
     * Format a value for axis display, using the eigenfield's declared units
     * and an appropriate SI prefix.
     */
    private String formatAxisValue(double value, SequenceChannel channel) {
        if (channel.isRfGate()) return String.format("%.1f", value);
        return formatWithUnits(value, editSession.unitsForChannel(channel));
    }

    /** Pick an SI prefix (μ, m, k, M) that keeps the numeric part in 0.1…1000. */
    private static String formatWithUnits(double value, String units) {
        if (units == null || units.isEmpty()) return String.format("%.3g", value);
        double abs = Math.abs(value);
        if (abs == 0) return "0 " + units;
        if (abs >= 1e9)  return String.format("%.2f G%s", value / 1e9, units);
        if (abs >= 1e6)  return String.format("%.2f M%s", value / 1e6, units);
        if (abs >= 1e3)  return String.format("%.2f k%s", value / 1e3, units);
        if (abs >= 1)    return String.format("%.2f %s",  value,       units);
        if (abs >= 1e-3) return String.format("%.2f m%s", value * 1e3, units);
        if (abs >= 1e-6) return String.format("%.2f μ%s", value * 1e6, units);
        if (abs >= 1e-9) return String.format("%.2f n%s", value * 1e9, units);
        return String.format("%.3g %s", value, units);
    }
}
