package ax.xz.mri.ui.timeline.element.clip;

import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.ui.edit.EditSession;
import ax.xz.mri.ui.menu.ContextMenuVocabulary;
import ax.xz.mri.ui.timeline.TimelineMetrics;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.css.PseudoClass;
import javafx.scene.control.Control;
import javafx.scene.control.Skin;

/**
 * Scene-graph node for one {@link SignalClip} on the timeline.
 *
 * <p>Replaces the canvas-painted clip rectangles of the previous renderer.
 * Each clip is now a real {@link Control} with:
 * <ul>
 *   <li>a {@link SignalClip} model that updates in place when the underlying
 *       observable changes;</li>
 *   <li>CSS pseudo-classes ({@code :selected}, {@code :primary},
 *       {@code :dragging}) bound to the {@link EditSession#selection} and
 *       {@link EditSession#preview} models — styling lives in
 *       {@code clip.css}, not in code;</li>
 *   <li>a {@link ClipSkin} that owns the gesture handlers (move, resize,
 *       amplitude, spline-point, click) and the waveform Canvas. JavaFX's
 *       MouseEvent capture model routes drag/release events to the press
 *       target, so each Skin is fully self-contained — no central router.</li>
 * </ul>
 *
 * <p>Layout: {@link #layoutXProperty} and {@link #prefWidthProperty} are bound
 * by {@link ClipSkin} to {@link TimelineMetrics#pxPerMicro} and the clip's
 * {@code startTime}/{@code duration}. Repositioning during pan/zoom or edit
 * happens automatically via the binding system.
 */
public final class Clip extends Control implements ax.xz.mri.ui.timeline.menu.TimelineContextMenuContributor {
    private static final PseudoClass SELECTED  = PseudoClass.getPseudoClass("selected");
    private static final PseudoClass PRIMARY   = PseudoClass.getPseudoClass("primary");
    private static final PseudoClass DRAGGING  = PseudoClass.getPseudoClass("dragging");
    private static final PseudoClass CENTRED   = PseudoClass.getPseudoClass("centred");

    private final EditSession session;
    private final TimelineMetrics metrics;
    private final ObjectProperty<SignalClip> model = new SimpleObjectProperty<>();
    private final ReadOnlyBooleanWrapper selected = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyBooleanWrapper primary  = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyBooleanWrapper dragging = new ReadOnlyBooleanWrapper(false);

    public Clip(EditSession session, TimelineMetrics metrics, SignalClip initial) {
        this.session = session;
        this.metrics = metrics;
        getStyleClass().add("clip");
        setFocusTraversable(true);
        model.set(initial);

        model.addListener((obs, o, n) -> {
            if (n == null) return;
            pseudoClassStateChanged(CENTRED, n.stayCentred());
            requestLayout();
        });

        var sel = session.selection;
        Runnable refreshFlags = () -> {
            String id = id();
            boolean isSel = id != null && sel.isSelected(id);
            boolean isPri = id != null && sel.isPrimary(id);
            selected.set(isSel);
            primary.set(isPri);
            pseudoClassStateChanged(SELECTED, isSel);
            pseudoClassStateChanged(PRIMARY, isPri);
        };
        sel.selected().addListener((javafx.collections.SetChangeListener<String>) c -> refreshFlags.run());
        sel.primary().addListener((obs, o, n) -> refreshFlags.run());
        model.addListener((obs, o, n) -> refreshFlags.run());
        refreshFlags.run();

        var preview = session.preview;
        Runnable refreshDragging = () -> {
            String id = id();
            boolean d = id != null && preview.draggingClipIds.contains(id);
            dragging.set(d);
            pseudoClassStateChanged(DRAGGING, d);
        };
        preview.draggingClipIds.addListener((javafx.collections.SetChangeListener<String>) c -> refreshDragging.run());
        model.addListener((obs, o, n) -> refreshDragging.run());
        refreshDragging.run();

        pseudoClassStateChanged(CENTRED, initial.stayCentred());
    }

    public EditSession session() { return session; }
    public TimelineMetrics metrics() { return metrics; }
    public ObjectProperty<SignalClip> modelProperty() { return model; }
    public SignalClip model() { return model.get(); }
    public String id() { var c = model.get(); return c == null ? null : c.id(); }

    public ReadOnlyBooleanProperty selectedProperty() { return selected.getReadOnlyProperty(); }
    public ReadOnlyBooleanProperty primaryProperty()  { return primary.getReadOnlyProperty(); }
    public ReadOnlyBooleanProperty draggingProperty() { return dragging.getReadOnlyProperty(); }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new ClipSkin(this);
    }

    @Override
    public String getUserAgentStylesheet() {
        return getClass().getResource("/ax/xz/mri/ui/timeline/clip.css").toExternalForm();
    }

    @Override
    public java.util.List<javafx.scene.control.MenuItem> menuItems() {
        var clip = model.get();
        if (clip == null) return java.util.List.of();
        var items = new java.util.ArrayList<javafx.scene.control.MenuItem>();

        items.add(ContextMenuVocabulary.CUT.item       (() -> { selectIfNotInSelection(); session.cutSelectedClips(); }));
        items.add(ContextMenuVocabulary.COPY.item      (() -> { selectIfNotInSelection(); session.copySelectedClips(); }));
        items.add(ContextMenuVocabulary.PASTE.item     (ax.xz.mri.ui.edit.EditSession.CLIP_CLIPBOARD.hasContent(), session::pasteAtCursor));
        items.add(ContextMenuVocabulary.separator());
        items.add(ContextMenuVocabulary.DUPLICATE.item (() -> { selectIfNotInSelection(); session.duplicateSelectedClips(); }));
        items.add(ContextMenuVocabulary.DELETE.item    (() -> { selectIfNotInSelection(); session.deleteSelectedClips(); }));
        items.add(ContextMenuVocabulary.separator());

        var recentre = new javafx.scene.control.MenuItem("Re-centre Media");
        recentre.setOnAction(e -> session.recentreClip(clip.id()));
        items.add(recentre);

        // Change-shape submenu — non-standard verb, plain Menu.
        var shape = new javafx.scene.control.Menu("Change Shape");
        for (var kind : ax.xz.mri.model.sequence.ClipKind.values()) {
            var item = new javafx.scene.control.MenuItem(kind.displayName());
            if (clip.shape().kind() == kind) item.setDisable(true);
            item.setOnAction(e -> session.changeClipKind(clip.id(), kind));
            shape.getItems().add(item);
        }
        items.add(shape);

        return items;
    }


    private void selectIfNotInSelection() {
        var c = model.get();
        if (c == null) return;
        if (!session.selection.isSelected(c.id())) session.selection.selectOnly(c.id());
    }
}
