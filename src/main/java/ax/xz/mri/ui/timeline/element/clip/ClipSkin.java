package ax.xz.mri.ui.timeline.element.clip;

import ax.xz.mri.model.sequence.ClipShape;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.ui.edit.EditPreview;
import ax.xz.mri.ui.edit.EditSession;
import ax.xz.mri.ui.timeline.TimelineMetrics;
import javafx.beans.binding.Bindings;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.control.SkinBase;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * Skin for {@link Clip}: visual layout + gesture handlers.
 *
 * <p>The Skin owns six gestures: <strong>move</strong>, <strong>resize-left</strong>,
 * <strong>resize-right</strong>, <strong>amplitude</strong>, <strong>spline-point</strong>,
 * and <strong>click</strong> (no-op selection-only). Each lives as a private state
 * object on this Skin — there is no central gesture router because JavaFX's
 * MouseEvent capture model already routes drag/release back to the press
 * target. ESC is wired via a scene-level filter that calls {@link #cancel} so
 * the gesture rolls back the transaction instead of committing it.
 *
 * <p>Visual layout:
 * <pre>
 *   ┌───────────────────────────────────────┐
 *   │ │                                   │ │   left/right resize handles (6px)
 *   │ │  waveform Canvas, label, splines  │ │
 *   │ │                                   │ │
 *   └───────────────────────────────────────┘
 * </pre>
 *
 * <p>Layout binding: {@link Clip#layoutXProperty} and {@link Clip#prefWidthProperty}
 * track {@link TimelineMetrics#pxPerMicro}, the clip's startTime, and its
 * duration. Pan/zoom and edit-time updates re-layout automatically.
 */
public final class ClipSkin extends SkinBase<Clip> {
    private static final double HANDLE_WIDTH = 6;
    private static final double SPLINE_HIT_RADIUS = 5;
    private static final double SPLINE_DRAW_RADIUS = 3;
    private static final WaveformCache CACHE = new WaveformCache();

    private final StackPane body = new StackPane();
    private final Canvas waveCanvas = new Canvas();
    private final Label shapeLabel = new Label();
    private final Region leftHandle = new Region();
    private final Region rightHandle = new Region();
    private final Pane splineLayer = new Pane();

    private Gesture gesture;

    private final EditSession session;
    private final TimelineMetrics metrics;

    public ClipSkin(Clip control) {
        super(control);
        this.session = control.session();
        this.metrics = control.metrics();

        body.getStyleClass().add("clip-body");
        waveCanvas.setMouseTransparent(true);
        shapeLabel.getStyleClass().add("clip-label");
        shapeLabel.setMouseTransparent(true);
        StackPane.setAlignment(shapeLabel, javafx.geometry.Pos.TOP_LEFT);
        splineLayer.getStyleClass().add("clip-spline-layer");
        splineLayer.setMouseTransparent(false);

        leftHandle.getStyleClass().add("clip-resize-handle");
        leftHandle.setCursor(Cursor.H_RESIZE);
        leftHandle.setPrefWidth(HANDLE_WIDTH);
        leftHandle.setMaxWidth(HANDLE_WIDTH);
        leftHandle.setPickOnBounds(true);

        rightHandle.getStyleClass().add("clip-resize-handle");
        rightHandle.setCursor(Cursor.H_RESIZE);
        rightHandle.setPrefWidth(HANDLE_WIDTH);
        rightHandle.setMaxWidth(HANDLE_WIDTH);
        rightHandle.setPickOnBounds(true);

        body.getChildren().addAll(waveCanvas, splineLayer, shapeLabel);
        getChildren().addAll(body, leftHandle, rightHandle);

        bindLayout(control);
        wireMouseHandlers(control);
        wireFocusAndScene(control);
        wireRepaint(control);
        installTooltip(control);
        rebuildSplinePoints();
        repaintWaveform();
    }

    // ── Layout bindings ──────────────────────────────────────────────────────

    private void bindLayout(Clip control) {
        var modelP = control.modelProperty();
        var pxPerMicro = metrics.pxPerMicro;
        var vStart = metrics.timeAxis.viewport.start;

        control.layoutXProperty().bind(Bindings.createDoubleBinding(() -> {
            var c = modelP.get();
            return c == null ? 0 : (c.startTime() - vStart.get()) * pxPerMicro.get();
        }, modelP, vStart, pxPerMicro));

        control.prefWidthProperty().bind(Bindings.createDoubleBinding(() -> {
            var c = modelP.get();
            // Always at least 6px wide so very short clips (e.g. 30μs RF pulses
            // viewed across a 5s CPMG sequence) stay visible and clickable —
            // sub-pixel clips were rendering as thin invisible strips.
            return c == null ? 0 : Math.max(6, c.duration() * pxPerMicro.get());
        }, modelP, pxPerMicro));
    }

    @Override
    protected void layoutChildren(double x, double y, double w, double h) {
        body.resizeRelocate(x, y, w, h);
        leftHandle.resizeRelocate(x, y, HANDLE_WIDTH, h);
        rightHandle.resizeRelocate(x + w - HANDLE_WIDTH, y, HANDLE_WIDTH, h);
        // Cap the inner waveform Canvas at the JavaFX texture ceiling so a
        // very long clip viewed at high zoom doesn't crash the editor. The
        // painter samples evenly across whatever width it's given — the
        // visible portion of the clip is always inside the cap.
        double canvasW = Math.min(w, 16000);
        double canvasH = Math.min(h, 16000);
        waveCanvas.setWidth(canvasW);
        waveCanvas.setHeight(canvasH);
        splineLayer.resize(w, h);
        repaintWaveform();
        repositionSplinePoints();
    }

    // ── Mouse wiring ─────────────────────────────────────────────────────────

    private void wireMouseHandlers(Clip control) {
        body.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            updateSelectionOnPress(control, e);
            if (e.isAltDown()) {
                startAmplitude(control, e);
            } else {
                startMove(control, e);
            }
            e.consume();
        });
        body.setOnMouseDragged(this::onDrag);
        body.setOnMouseReleased(this::onRelease);

        leftHandle.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            session.beginTransaction("Resize clip");
            gesture = new Gesture.ResizeLeft();
            session.preview.active.set(EditPreview.GestureKind.RESIZE_LEFT);
            session.preview.draggingClipIds.add(control.id());
            e.consume();
        });
        leftHandle.setOnMouseDragged(this::onDrag);
        leftHandle.setOnMouseReleased(this::onRelease);

        rightHandle.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            session.beginTransaction("Resize clip");
            gesture = new Gesture.ResizeRight();
            session.preview.active.set(EditPreview.GestureKind.RESIZE_RIGHT);
            session.preview.draggingClipIds.add(control.id());
            e.consume();
        });
        rightHandle.setOnMouseDragged(this::onDrag);
        rightHandle.setOnMouseReleased(this::onRelease);
    }

    private void updateSelectionOnPress(Clip control, MouseEvent e) {
        var sel = session.selection;
        String id = control.id();
        if (e.isShortcutDown()) sel.toggle(id);
        else if (e.isShiftDown()) sel.add(id);
        else if (!sel.isSelected(id)) sel.selectOnly(id);
        else sel.primary().set(id);
    }

    private void startMove(Clip control, MouseEvent e) {
        var clip = control.model();
        if (clip == null) return;
        double mouseTime = mouseTimeFromBody(e);
        boolean multi = session.selection.selected().size() > 1
                       && session.selection.isSelected(clip.id());
        gesture = new Gesture.Move(
            mouseTime - clip.startTime(),
            mouseTime,
            multi);
        session.beginTransaction(multi ? "Move clips" : "Move clip");
        session.preview.active.set(EditPreview.GestureKind.MOVE_CLIP);
        if (multi) {
            for (var c : session.selectedClips()) session.preview.draggingClipIds.add(c.id());
        } else {
            session.preview.draggingClipIds.add(clip.id());
        }
    }

    private void startAmplitude(Clip control, MouseEvent e) {
        var clip = control.model();
        if (clip == null) return;
        gesture = new Gesture.Amplitude(e.getSceneY(), clip.amplitude());
        session.beginTransaction("Adjust amplitude");
        session.preview.active.set(EditPreview.GestureKind.AMPLITUDE);
        session.preview.draggingClipIds.add(clip.id());
    }

    private void onDrag(MouseEvent e) {
        var control = getSkinnable();
        var clip = control.model();
        if (clip == null || gesture == null) return;
        switch (gesture) {
            case Gesture.Move m -> handleMove(control, clip, m, e);
            case Gesture.ResizeLeft __ -> handleResizeLeft(clip, e);
            case Gesture.ResizeRight __ -> handleResizeRight(clip, e);
            case Gesture.Amplitude a -> handleAmplitude(clip, a, e);
            case Gesture.SplinePoint sp -> handleSplinePoint(control, clip, sp, e);
        }
        e.consume();
    }

    private void onRelease(MouseEvent e) {
        if (gesture == null) return;
        boolean wasResize = gesture instanceof Gesture.ResizeLeft || gesture instanceof Gesture.ResizeRight;
        if (wasResize) {
            var clip = getSkinnable().model();
            if (clip != null && clip.stayCentred()) session.recentreClip(clip.id());
        }
        session.endTransaction();
        clearGestureState();
        e.consume();
    }

    private void handleMove(Clip control, SignalClip clip, Gesture.Move m, MouseEvent e) {
        double mouseTime = mouseTimeFromBody(e);
        if (m.multi()) {
            double delta = mouseTime - m.multiAnchorTime();
            double rawStart = clip.startTime() + delta;
            double snapped = session.snap.snapTime(rawStart);
            if (snapped != rawStart) {
                session.preview.snapTargetMicros.set(snapped);
                delta += snapped - rawStart;
            } else {
                session.preview.snapTargetMicros.set(Double.NaN);
            }
            session.moveSelectedClips(delta);
            // Re-anchor so subsequent drag deltas are relative to the new mouse-time.
            gesture = new Gesture.Move(m.anchorOffsetMicros(), mouseTime, true);
        } else {
            double newStart = Math.max(0, mouseTime - m.anchorOffsetMicros());
            double snapped = session.snap.snapTime(newStart);
            if (snapped != newStart) session.preview.snapTargetMicros.set(snapped);
            else session.preview.snapTargetMicros.set(Double.NaN);
            session.moveClip(clip.id(), snapped);
        }
        // Cross-track re-route — find the lane under the mouse and migrate if changed.
        var newTrackId = laneAtSceneY(control, e.getSceneY());
        if (newTrackId != null && !newTrackId.equals(clip.trackId())) {
            session.changeClipTrack(clip.id(), newTrackId);
        }
    }

    private void handleResizeLeft(SignalClip clip, MouseEvent e) {
        double mouseTime = mouseTimeFromBody(e);
        double snapped = session.snap.snapTime(mouseTime);
        if (snapped != mouseTime) session.preview.snapTargetMicros.set(snapped);
        else session.preview.snapTargetMicros.set(Double.NaN);
        session.resizeClipLeft(clip.id(), snapped);
    }

    private void handleResizeRight(SignalClip clip, MouseEvent e) {
        double mouseTime = mouseTimeFromBody(e);
        double snapped = session.snap.snapTime(mouseTime);
        if (snapped != mouseTime) session.preview.snapTargetMicros.set(snapped);
        else session.preview.snapTargetMicros.set(Double.NaN);
        session.resizeClipRight(clip.id(), snapped);
    }

    private void handleAmplitude(SignalClip clip, Gesture.Amplitude a, MouseEvent e) {
        // Vertical drag: 100 px = full amplitude swing.
        double dy = a.anchorSceneY() - e.getSceneY();
        double newAmp = a.originalAmplitude() + dy / 100.0 * Math.max(1, Math.abs(a.originalAmplitude()) + 1);
        session.setClipAmplitude(clip.id(), newAmp);
    }

    private void handleSplinePoint(Clip control, SignalClip clip, Gesture.SplinePoint sp, MouseEvent e) {
        if (!(clip.shape() instanceof ClipShape.Spline spline)) return;
        if (sp.pointIndex() < 0 || sp.pointIndex() >= spline.points().size()) return;
        // u: position along the clip's duration, in [0, 1]
        // value: amplitude scale, in [-1, 1]
        double localX = e.getX();
        double localY = e.getY();
        double width = control.getWidth();
        double height = control.getHeight();
        double u = Math.max(0, Math.min(1, localX / width));
        double midY = height * 0.5;
        double value = Math.max(-1, Math.min(1, (midY - localY) / midY));
        var newPoint = new ClipShape.Spline.Point(u, value);
        session.updateSplinePoint(clip.id(), sp.pointIndex(), newPoint);
    }

    private void cancel() {
        if (gesture == null) return;
        session.cancelTransaction();
        clearGestureState();
    }

    private void clearGestureState() {
        gesture = null;
        session.preview.active.set(null);
        session.preview.snapTargetMicros.set(Double.NaN);
        session.preview.draggingClipIds.clear();
    }

    // ── Spline points ────────────────────────────────────────────────────────

    private void rebuildSplinePoints() {
        splineLayer.getChildren().clear();
        var clip = getSkinnable().model();
        if (clip == null) return;
        if (!getSkinnable().selectedProperty().get()) return;
        if (!(clip.shape() instanceof ClipShape.Spline spline)) return;
        for (int i = 0; i < spline.points().size(); i++) {
            int index = i;
            var dot = new Circle(SPLINE_DRAW_RADIUS);
            dot.getStyleClass().add("clip-spline-point");
            dot.setOnMousePressed(e -> {
                if (e.getButton() != MouseButton.PRIMARY) return;
                gesture = new Gesture.SplinePoint(index);
                session.beginTransaction("Move spline point");
                session.preview.active.set(EditPreview.GestureKind.SPLINE_POINT);
                session.preview.draggingClipIds.add(getSkinnable().id());
                e.consume();
            });
            dot.setOnMouseDragged(this::onDrag);
            dot.setOnMouseReleased(this::onRelease);
            splineLayer.getChildren().add(dot);
        }
        repositionSplinePoints();
    }

    private void repositionSplinePoints() {
        var clip = getSkinnable().model();
        if (clip == null) return;
        if (!(clip.shape() instanceof ClipShape.Spline spline)) return;
        double width = getSkinnable().getWidth();
        double height = getSkinnable().getHeight();
        double midY = height * 0.5;
        for (int i = 0; i < splineLayer.getChildren().size() && i < spline.points().size(); i++) {
            if (splineLayer.getChildren().get(i) instanceof Circle dot) {
                var pt = spline.points().get(i);
                dot.setCenterX(pt.t() * width);
                dot.setCenterY(midY - pt.value() * midY);
            }
        }
    }

    // ── Painting & repaints ──────────────────────────────────────────────────

    private void wireRepaint(Clip control) {
        shapeLabel.textProperty().bind(Bindings.createStringBinding(() -> {
            var c = control.model();
            return c == null ? "" : c.shape().displayName();
        }, control.modelProperty()));
        control.modelProperty().addListener((obs, o, n) -> {
            rebuildSplinePoints();
            repaintWaveform();
        });
        control.selectedProperty().addListener((obs, o, n) -> rebuildSplinePoints());
    }

    private void repaintWaveform() {
        var g = waveCanvas.getGraphicsContext2D();
        double w = waveCanvas.getWidth();
        double h = waveCanvas.getHeight();
        g.clearRect(0, 0, w, h);
        var clip = getSkinnable().model();
        if (clip == null || w <= 0 || h <= 0) return;
        var stroke = getSkinnable().primaryProperty().get()
            ? ax.xz.mri.ui.theme.ThemeTokens.Tone.ACCENT
            : ax.xz.mri.ui.theme.ThemeTokens.Tone.ACCENT.deriveColor(0, 1, 1, 0.85);
        WaveformPainter.paint(g, CACHE, clip, w, h, peakDisplay(clip), stroke);
    }

    private double peakDisplay(SignalClip clip) {
        // Half-height represents the larger of (this clip's amplitude, 1) so a
        // 0.5-amp clip doesn't render full-bleed when neighbours are unit-amp.
        double a = Math.abs(clip.amplitude());
        return Math.max(1.0, a);
    }

    // ── Tooltip ──────────────────────────────────────────────────────────────

    private void installTooltip(Clip control) {
        var tip = new Tooltip();
        tip.textProperty().bind(Bindings.createStringBinding(() -> {
            var c = control.model();
            if (c == null) return "";
            return c.shape().displayName()
                + "  " + String.format("%.0fµs–%.0fµs (%.0fµs)",
                                       c.startTime(), c.endTime(), c.duration())
                + "  amp=" + String.format("%.3f", c.amplitude())
                + (c.stayCentred() ? "  · centred" : "");
        }, control.modelProperty()));
        Tooltip.install(this.body, tip);
    }

    // ── Scene + focus + ESC ──────────────────────────────────────────────────

    private void wireFocusAndScene(Clip control) {
        control.focusedProperty().addListener((obs, was, now) -> {
            if (!now && gesture != null) cancel();
        });
        control.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null) oldScene.removeEventFilter(KeyEvent.KEY_PRESSED, escFilter);
            if (newScene != null) newScene.addEventFilter(KeyEvent.KEY_PRESSED, escFilter);
        });
    }

    private final java.util.function.Consumer<KeyEvent> escFilterImpl = e -> {
        if (e.getCode() == KeyCode.ESCAPE && gesture != null) {
            cancel();
            e.consume();
        }
    };
    private final javafx.event.EventHandler<KeyEvent> escFilter = escFilterImpl::accept;

    // ── Helpers ──────────────────────────────────────────────────────────────

    private double mouseTimeFromBody(MouseEvent e) {
        // Mouse coords are local to the press-target Node. We need lane-local X
        // — convert via scene.
        var lane = laneOf(getSkinnable());
        if (lane == null) return metrics.xToTime(e.getX() + getSkinnable().getLayoutX());
        var laneLocal = lane.sceneToLocal(e.getSceneX(), e.getSceneY());
        return metrics.xToTime(laneLocal.getX());
    }

    private static javafx.scene.Node laneOf(Clip control) {
        var p = control.getParent();
        return p; // the TrackLane node (or whatever Pane is hosting clips)
    }

    private String laneAtSceneY(Clip control, double sceneY) {
        // Walk parent chain to find the TrackStack (the VBox of TrackLanes),
        // then find which TrackLane child contains sceneY.
        var parent = control.getParent();
        if (parent == null) return null;
        var stack = parent.getParent();
        if (stack == null) return null;
        for (var child : stack.getChildrenUnmodifiable()) {
            var b = child.localToScene(child.getBoundsInLocal());
            if (sceneY >= b.getMinY() && sceneY <= b.getMaxY()
                && child instanceof ax.xz.mri.ui.timeline.element.track.TrackLane lane) {
                return lane.trackId();
            }
        }
        return null;
    }

    // ── Gesture state ────────────────────────────────────────────────────────

    private sealed interface Gesture {
        record Move(double anchorOffsetMicros, double multiAnchorTime, boolean multi) implements Gesture {}
        record ResizeLeft() implements Gesture {}
        record ResizeRight() implements Gesture {}
        record Amplitude(double anchorSceneY, double originalAmplitude) implements Gesture {}
        record SplinePoint(int pointIndex) implements Gesture {}
    }
}
