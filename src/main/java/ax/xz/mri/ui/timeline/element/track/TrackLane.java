package ax.xz.mri.ui.timeline.element.track;

import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.model.sequence.Track;
import ax.xz.mri.ui.edit.EditPreview;
import ax.xz.mri.ui.edit.EditSession;
import ax.xz.mri.ui.timeline.TimelineMetrics;
import ax.xz.mri.ui.timeline.element.clip.Clip;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableMap;
import javafx.css.PseudoClass;
import javafx.scene.Cursor;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Rectangle;

import java.util.HashSet;
import java.util.Set;

/**
 * One arrangement lane in the timeline editor.
 *
 * <p>Owns the {@link Clip} children for a single {@link Track}. Layout is
 * absolute positioning (clips bind their {@code layoutX}/{@code prefWidth} to
 * {@link TimelineMetrics}); the lane itself just tracks its track's id and
 * acts as the press-target for the two empty-lane gestures:
 * <ul>
 *   <li><strong>rubber-band selection</strong> — click on empty space and
 *       drag to draw a selection rectangle that overlaps clips by their
 *       time × track-id;</li>
 *   <li><strong>drag-to-create</strong> — when the toolbar's active clip kind
 *       is non-null, drag on empty space to create a new clip of that kind.</li>
 * </ul>
 *
 * <p>Drop-target for cross-lane clip drops is implicit: the {@code ClipSkin}
 * walks the parent track-stack to find which lane the mouse is over and calls
 * {@link EditSession#changeClipTrack}. This lane class doesn't need to
 * register a JavaFX DnD handler for that case — it's a same-window mouse
 * drag.
 */
public final class TrackLane extends Pane implements ax.xz.mri.ui.timeline.menu.TimelineContextMenuContributor {
    private static final PseudoClass SELECTED_LANE = PseudoClass.getPseudoClass("selected");

    private final EditSession session;
    private final TimelineMetrics metrics;
    private final ObjectProperty<Track> track = new SimpleObjectProperty<>();
    private final ObservableMap<String, Clip> clipsById = FXCollections.observableHashMap();
    private final Rectangle rubberBand = new Rectangle();

    private RubberBand rubber;
    private CreatePreview createDrag;
    private java.util.function.Supplier<ax.xz.mri.model.sequence.ClipKind> activeCreationKind = () -> null;

    public TrackLane(EditSession session, TimelineMetrics metrics, Track initialTrack) {
        this.session = session;
        this.metrics = metrics;
        getStyleClass().add("track-lane");
        setMinHeight(40);
        setPrefHeight(56);
        track.set(initialTrack);

        // Clip rendering to the lane's actual bounds — without this, clips
        // that lay out beyond the viewport edge (heavy zoom-in case) render
        // outside the lane and bleed into adjacent UI.
        var clipRect = new Rectangle();
        clipRect.widthProperty().bind(widthProperty());
        clipRect.heightProperty().bind(heightProperty());
        setClip(clipRect);

        rubberBand.getStyleClass().add("rubber-band");
        rubberBand.setVisible(false);
        rubberBand.setMouseTransparent(true);
        getChildren().add(rubberBand);

        rebuildClipsForTrack();
        session.clips.addListener((javafx.collections.ListChangeListener<SignalClip>) c -> rebuildClipsForTrack());
        track.addListener((obs, o, n) -> rebuildClipsForTrack());

        clipsById.addListener((MapChangeListener<String, Clip>) ch -> {
            if (ch.wasRemoved() && ch.getValueRemoved() != null) {
                getChildren().remove(ch.getValueRemoved());
            }
            if (ch.wasAdded() && ch.getValueAdded() != null) {
                if (!getChildren().contains(ch.getValueAdded())) {
                    getChildren().add(ch.getValueAdded());
                }
            }
        });

        wireMouseHandlers();
    }

    // Don't let our preferred size be driven by the layout positions of our
    // children. Without these overrides, a Clip whose layoutX is beyond the
    // viewport edge (because we're zoomed in) makes Pane.computePrefWidth
    // return that out-of-band X — which propagates up the layout tree, grows
    // the lane stack, the editor pane, and ultimately the shell. Returning
    // the explicit pref values keeps every cursor scrub and viewport zoom
    // strictly internal to the timeline.
    @Override protected double computePrefWidth(double h)  { return getMinWidth() < 0 ? 0 : getMinWidth(); }
    @Override protected double computePrefHeight(double w) { return getPrefHeight() < 0 ? 56 : getPrefHeight(); }
    @Override protected double computeMinWidth(double h)   { return 0; }

    /**
     * Position and size each clip child. The Clip's {@code layoutX} and
     * {@code prefWidth} are bound to {@link TimelineMetrics#pxPerMicro} by its
     * Skin; this method takes care of the vertical placement (centred in the
     * lane with a small inset so adjacent clips don't visually butt against
     * each other), so the clip body grows with the lane height instead of
     * sitting as a thin strip at the top.
     */
    @Override
    protected void layoutChildren() {
        double laneHeight = getHeight();
        double inset = 4;
        double clipHeight = Math.max(20, laneHeight - inset * 2);
        for (var child : getChildren()) {
            if (child instanceof Clip clip) {
                clip.setLayoutY(inset);
                clip.resize(clip.prefWidth(-1), clipHeight);
            }
        }
        // Rubber-band rectangle: position by the press handler — don't
        // rewrite it here.
        if (rubberBand.isVisible() && rubberBand.getHeight() == 0) {
            rubberBand.setHeight(laneHeight);
        }
    }

    public void setActiveCreationKind(java.util.function.Supplier<ax.xz.mri.model.sequence.ClipKind> supplier) {
        this.activeCreationKind = supplier == null ? () -> null : supplier;
    }

    public ObjectProperty<Track> trackProperty() { return track; }
    public Track track() { return track.get(); }
    public String trackId() { var t = track.get(); return t == null ? null : t.id(); }

    // ── Clip child management ────────────────────────────────────────────────

    private void rebuildClipsForTrack() {
        var t = track.get();
        if (t == null) {
            clipsById.clear();
            return;
        }
        Set<String> seen = new HashSet<>();
        for (var clip : session.clips) {
            if (!t.id().equals(clip.trackId())) continue;
            seen.add(clip.id());
            var existing = clipsById.get(clip.id());
            if (existing == null) {
                var node = new Clip(session, metrics, clip);
                clipsById.put(clip.id(), node);
            } else {
                existing.modelProperty().set(clip);
            }
        }
        clipsById.keySet().removeIf(id -> !seen.contains(id));
    }

    // ── Mouse handlers (rubber-band + drag-to-create on empty lane) ─────────

    private void wireMouseHandlers() {
        setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (e.getTarget() != this) return; // clicked through a Clip child — ignore
            var kind = activeCreationKind.get();
            if (kind != null) {
                createDrag = new CreatePreview(kind, e.getX(), metrics.xToTime(e.getX()));
                rubberBand.setVisible(true);
                rubberBand.setX(e.getX());
                rubberBand.setY(0);
                rubberBand.setWidth(1);
                rubberBand.setHeight(getHeight());
                session.preview.active.set(EditPreview.GestureKind.CREATE_CLIP);
                e.consume();
                return;
            }
            if (!e.isShiftDown() && !e.isShortcutDown()) session.selection.clear();
            rubber = new RubberBand(e.getX(), e.getY(), e.isShiftDown() || e.isShortcutDown());
            rubberBand.setVisible(true);
            rubberBand.setX(e.getX());
            rubberBand.setY(e.getY());
            rubberBand.setWidth(0);
            rubberBand.setHeight(0);
            session.preview.active.set(EditPreview.GestureKind.RUBBER_BAND);
            setCursor(Cursor.CROSSHAIR);
            e.consume();
        });

        setOnMouseDragged(e -> {
            if (createDrag != null) {
                double minX = Math.min(createDrag.startX(), e.getX());
                double maxX = Math.max(createDrag.startX(), e.getX());
                rubberBand.setX(minX);
                rubberBand.setWidth(maxX - minX);
                rubberBand.setY(0);
                rubberBand.setHeight(getHeight());
                e.consume();
                return;
            }
            if (rubber != null) {
                double minX = Math.min(rubber.startX(), e.getX());
                double maxX = Math.max(rubber.startX(), e.getX());
                double minY = Math.min(rubber.startY(), e.getY());
                double maxY = Math.max(rubber.startY(), e.getY());
                rubberBand.setX(minX);
                rubberBand.setY(minY);
                rubberBand.setWidth(maxX - minX);
                rubberBand.setHeight(maxY - minY);
                e.consume();
            }
        });

        setOnMouseReleased(e -> {
            if (createDrag != null) {
                double endTime = metrics.xToTime(e.getX());
                double startTime = Math.min(createDrag.startTime(), endTime);
                double duration = Math.max(session.dt.get(), Math.abs(endTime - createDrag.startTime()));
                String tid = trackId();
                if (tid != null && duration >= session.dt.get()) {
                    var clip = session.createClipCentred(createDrag.kind(), tid, startTime, duration);
                    session.addClip(clip);
                    session.selection.selectOnly(clip.id());
                }
                createDrag = null;
                rubberBand.setVisible(false);
                session.preview.active.set(null);
                setCursor(Cursor.DEFAULT);
                e.consume();
                return;
            }
            if (rubber != null) {
                double minTime = metrics.xToTime(Math.min(rubber.startX(), e.getX()));
                double maxTime = metrics.xToTime(Math.max(rubber.startX(), e.getX()));
                String tid = trackId();
                if (tid != null) {
                    session.selectClipsInRegion(minTime, maxTime, Set.of(tid), rubber.additive());
                }
                rubber = null;
                rubberBand.setVisible(false);
                session.preview.active.set(null);
                setCursor(Cursor.DEFAULT);
                e.consume();
            }
        });
    }

    private record RubberBand(double startX, double startY, boolean additive) {}
    private record CreatePreview(ax.xz.mri.model.sequence.ClipKind kind, double startX, double startTime) {}

    // ── Context menu (TimelineContextMenuContributor) ───────────────────────

    @Override
    public java.util.List<javafx.scene.control.MenuItem> menuItems() {
        // Lane-level (empty-area) context menu — appears when right-clicking
        // somewhere on the lane that isn't a clip.
        return menuItemsForChildren();
    }

    @Override
    public java.util.List<javafx.scene.control.MenuItem> menuItemsForChildren() {
        var items = new java.util.ArrayList<javafx.scene.control.MenuItem>();

        var add = new javafx.scene.control.Menu("Add Clip");
        for (var kind : ax.xz.mri.model.sequence.ClipKind.values()) {
            var item = new javafx.scene.control.MenuItem(kind.displayName());
            item.setOnAction(e -> {
                String tid = trackId();
                if (tid == null) return;
                double t = metrics.timeAxis.cursor.time.get();
                double dur = Math.max(session.dt.get(), 100.0);
                var clip = session.createClipCentred(kind, tid, t, dur);
                session.addClip(clip);
                session.selection.selectOnly(clip.id());
            });
            add.getItems().add(item);
        }
        items.add(add);

        items.add(ax.xz.mri.ui.menu.ContextMenuVocabulary.PASTE.item(
            ax.xz.mri.ui.edit.EditSession.CLIP_CLIPBOARD.hasContent(),
            () -> {
                String tid = trackId();
                double t = metrics.timeAxis.cursor.time.get();
                session.pasteAtTime(t, tid);
            }));

        items.add(ax.xz.mri.ui.menu.ContextMenuVocabulary.SELECT_ALL.item(() -> {
            String tid = trackId();
            if (tid == null) return;
            var ids = session.clips.stream()
                .filter(c -> tid.equals(c.trackId()))
                .map(c -> c.id())
                .toList();
            session.selection.replaceWith(ids, ids.isEmpty() ? null : ids.get(0));
        }));

        return items;
    }
}
