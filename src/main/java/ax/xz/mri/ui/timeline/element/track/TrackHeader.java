package ax.xz.mri.ui.timeline.element.track;

import ax.xz.mri.hardware.HardwarePluginRegistry;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.sequence.Track;
import ax.xz.mri.ui.edit.EditSession;
import ax.xz.mri.ui.widget.RouteChip;
import ax.xz.mri.ui.widget.StudioIcons;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * Gutter row for one timeline track — SolidWorks feature-tree style.
 *
 * <p>Layout (left-to-right inside the gutter):
 * <pre>
 *   ┌──┬─────────────────────────────────────┐
 *   │■ │ ▾  Source 3151                      │   ← name row
 *   │  │   ●sim Source 3151   ●hw —          │   ← routing chips
 *   └──┴─────────────────────────────────────┘
 *    │
 *    └─ 4 px coloured rail (track identity)
 * </pre>
 *
 * <p>Routing chips replace the previous "label + ComboBox" grid: each chip
 * is itself a popup button showing its routed channel name (or "—" when
 * unrouted). The dot's tone communicates context (blue = sim, amber = hw)
 * and the chip's lit/dim state communicates routed/unrouted. The hw chip
 * is disabled (with a tooltip) when no hardware config is bound.
 *
 * <p>The chevron is a bespoke {@link StudioIcons} glyph; the coloured rail
 * is keyed deterministically off the track id so identity is stable across
 * sessions without needing a schema change.
 *
 * <p>JavaFX DnD reorders entire rows: drag a header, drop on another
 * header. The drag image is captured synchronously at press time so the
 * row's children are still laid out when the snapshot fires — the previous
 * version captured AFTER layout invalidation, producing an empty drag
 * image and the visible "—" placeholder bug.
 */
public final class TrackHeader extends HBox implements ax.xz.mri.ui.timeline.menu.TimelineContextMenuContributor {
    public static final DataFormat TRACK_ID_FMT = new DataFormat("application/x-mri-track-id");
    private static final PseudoClass DRAG_OVER = PseudoClass.getPseudoClass("drag-over");
    private static final PseudoClass COLLAPSED = PseudoClass.getPseudoClass("collapsed");

    /** Stable palette for per-track identity rails. Eight muted hues, none safety-orange. */
    private static final Color[] RAIL_COLOURS = new Color[] {
        Color.web("#0f5fa6"), Color.web("#4d7e3e"), Color.web("#a05a0e"), Color.web("#7a3a8a"),
        Color.web("#1a7a8a"), Color.web("#a4324a"), Color.web("#5d6f88"), Color.web("#8a6a1a"),
    };

    private final EditSession session;
    private final ObjectProperty<Track> track = new SimpleObjectProperty<>();

    private final Region colourRail = new Region();
    private final TextField nameField = new TextField();
    private final Label collapseChevron = new Label();
    private final RouteChip simChip = new RouteChip(RouteChip.Context.SIM);
    private final RouteChip hwChip  = new RouteChip(RouteChip.Context.HW);
    private final VBox bodyColumn = new VBox(2);

    public TrackHeader(EditSession session, Track initialTrack) {
        this.session = session;
        getStyleClass().add("track-header");
        setMinWidth(180);
        setPrefWidth(180);
        setMaxWidth(180);
        setSpacing(0);
        setAlignment(Pos.TOP_LEFT);
        track.set(initialTrack);

        // Colour rail (4 px, full-height) — left edge
        colourRail.getStyleClass().add("track-colour-rail");
        colourRail.setMinWidth(4);
        colourRail.setPrefWidth(4);
        colourRail.setMaxWidth(4);

        // Body column (name + chips), padded
        bodyColumn.setPadding(new Insets(4, 8, 4, 6));
        bodyColumn.setFillWidth(true);
        HBox.setHgrow(bodyColumn, Priority.ALWAYS);
        bodyColumn.getChildren().addAll(buildNameRow(), buildChipRow());

        getChildren().addAll(colourRail, bodyColumn);

        bindToTrack();
        wireUserEdits();
        wireDragAndDrop();
        wireCollapse();
        refreshHwAvailability();

        session.activeHardwareConfigId.addListener((obs, o, n) -> refreshHwAvailability());
        session.collapsedTrackIds.addListener((javafx.collections.SetChangeListener<String>) c -> {
            var t = track.get();
            if (t == null) return;
            applyCollapsedState(session.isTrackCollapsed(t.id()));
        });
        applyCollapsedState(session.isTrackCollapsed(initialTrack.id()));
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    private HBox buildNameRow() {
        var row = new HBox(4);
        row.setAlignment(Pos.CENTER_LEFT);

        collapseChevron.setGraphic(StudioIcons.of(StudioIcons.Kind.CHEVRON_DOWN, 12));
        collapseChevron.getStyleClass().add("track-collapse-chevron");
        collapseChevron.setCursor(Cursor.HAND);

        nameField.getStyleClass().add("track-name");
        HBox.setHgrow(nameField, Priority.ALWAYS);

        row.getChildren().addAll(collapseChevron, nameField);
        return row;
    }

    private VBox buildChipRow() {
        // Stack the routing chips vertically — each gets the full gutter
        // width (~170 px after rail + padding), enough for the dot +
        // context label + route name without truncation.
        var col = new VBox(2, simChip, hwChip);
        col.setAlignment(Pos.CENTER_LEFT);
        col.setPadding(new Insets(0, 0, 0, 16)); // align under the name field
        simChip.setMaxWidth(Double.MAX_VALUE);
        hwChip.setMaxWidth(Double.MAX_VALUE);
        return col;
    }

    private void applyCollapsedState(boolean collapsed) {
        pseudoClassStateChanged(COLLAPSED, collapsed);
        collapseChevron.setGraphic(StudioIcons.of(
            collapsed ? StudioIcons.Kind.CHEVRON_RIGHT : StudioIcons.Kind.CHEVRON_DOWN, 12));
        nameField.setEditable(!collapsed);
        // When collapsed, hide the chip row to fit inside the 22 px lane.
        if (bodyColumn.getChildren().size() >= 2) {
            var chipRow = bodyColumn.getChildren().get(1);
            chipRow.setVisible(!collapsed);
            chipRow.setManaged(!collapsed);
        }
    }

    public ObjectProperty<Track> trackProperty() { return track; }
    public String trackId() { var t = track.get(); return t == null ? null : t.id(); }

    // ── Bindings ────────────────────────────────────────────────────────────

    private void bindToTrack() {
        track.addListener((obs, o, n) -> populateFromTrack(n));
        populateFromTrack(track.get());
    }

    private void populateFromTrack(Track t) {
        if (t == null) return;
        if (!nameField.isFocused()) nameField.setText(t.name());

        // Colour rail — derived from track id; stable across sessions.
        var hue = RAIL_COLOURS[Math.floorMod(t.id().hashCode(), RAIL_COLOURS.length)];
        colourRail.setStyle("-fx-background-color: " + toRgba(hue) + ";");

        // Sim chip ----------------------------------------------------------
        simChip.setRouteName(displayChannel(t.simChannel()));
        if (t.simChannel() != null) {
            simChip.withTooltip("Sim: " + describeChannel(t.simChannel()));
        } else {
            simChip.withTooltip("Sim routing — click to set");
        }
        rebuildSimChipMenu(t);

        // Hw chip -----------------------------------------------------------
        hwChip.setRouteName(displayChannel(t.hardwareChannel()));
        if (t.hardwareChannel() != null) {
            hwChip.withTooltip("Hardware: " + describeChannel(t.hardwareChannel()));
        } else {
            hwChip.withTooltip("Hardware routing — bind a hardware config to enable");
        }
        rebuildHwChipMenu(t);

        boolean collapsed = session.isTrackCollapsed(t.id());
        pseudoClassStateChanged(COLLAPSED, collapsed);
    }

    private void rebuildSimChipMenu(Track t) {
        simChip.getItems().clear();
        var none = new MenuItem("— none —");
        none.setOnAction(e -> session.setTrackSimChannel(t.id(), null));
        simChip.getItems().add(none);
        for (var ch : session.availableOutputChannels()) {
            var item = new MenuItem(describeChannel(ch));
            item.setOnAction(e -> session.setTrackSimChannel(t.id(), ch));
            simChip.getItems().add(item);
        }
    }

    private void rebuildHwChipMenu(Track t) {
        hwChip.getItems().clear();
        var hwConfig = session.activeHardwareConfigDoc();
        if (hwConfig == null || hwConfig.config() == null) return;
        var none = new MenuItem("— none —");
        none.setOnAction(e -> session.setTrackHardwareChannel(t.id(), null));
        hwChip.getItems().add(none);
        for (var ch : session.availableHardwareChannels(hwConfig)) {
            var item = new MenuItem(describeChannel(ch));
            item.setOnAction(e -> session.setTrackHardwareChannel(t.id(), ch));
            hwChip.getItems().add(item);
        }
    }

    private void refreshHwAvailability() {
        var hwConfig = session.activeHardwareConfigDoc();
        boolean available = hwConfig != null && hwConfig.config() != null
            && HardwarePluginRegistry.byId(hwConfig.config().pluginId()).isPresent();
        hwChip.setDisable(!available);
        if (track.get() != null) rebuildHwChipMenu(track.get());
    }

    private void wireUserEdits() {
        nameField.setOnAction(e -> commitName());
        nameField.focusedProperty().addListener((obs, was, isNow) -> {
            if (was && !isNow) commitName();
        });
    }

    private void commitName() {
        var t = track.get();
        if (t == null) return;
        String text = nameField.getText().trim();
        if (text.isEmpty() || text.equals(t.name())) {
            nameField.setText(t.name());
            return;
        }
        session.renameTrack(t.id(), text);
    }

    private void wireCollapse() {
        collapseChevron.setOnMouseClicked(e -> {
            var t = track.get();
            if (t == null) return;
            session.setTrackCollapsed(t.id(), !session.isTrackCollapsed(t.id()));
        });
    }

    // ── DnD reorder ─────────────────────────────────────────────────────────

    private void wireDragAndDrop() {
        setOnDragDetected(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            String id = trackId();
            if (id == null || nameField.isFocused()) return;
            // Capture the drag image SYNCHRONOUSLY before any layout
            // invalidation. Calling snapshot() AFTER startDragAndDrop()
            // (the previous order) would race with the JavaFX DnD machinery
            // re-validating the row, leaving the snapshot empty and the
            // dragged row visually showing "—" placeholders.
            WritableImage img = renderDragImage();
            Dragboard db = startDragAndDrop(TransferMode.MOVE);
            ClipboardContent cc = new ClipboardContent();
            cc.put(TRACK_ID_FMT, id);
            db.setContent(cc);
            if (img != null) db.setDragView(img);
            e.consume();
        });

        setOnDragOver(e -> {
            if (e.getGestureSource() == this) return;
            if (e.getDragboard().hasContent(TRACK_ID_FMT)) {
                e.acceptTransferModes(TransferMode.MOVE);
                pseudoClassStateChanged(DRAG_OVER, true);
            }
            e.consume();
        });

        setOnDragExited(e -> {
            pseudoClassStateChanged(DRAG_OVER, false);
            e.consume();
        });

        setOnDragDropped(e -> {
            pseudoClassStateChanged(DRAG_OVER, false);
            var db = e.getDragboard();
            if (!db.hasContent(TRACK_ID_FMT)) {
                e.setDropCompleted(false);
                return;
            }
            String movedId = (String) db.getContent(TRACK_ID_FMT);
            String myId = trackId();
            if (movedId == null || myId == null || movedId.equals(myId)) {
                e.setDropCompleted(false);
                return;
            }
            int targetIndex = session.tracks.indexOf(session.findTrack(myId));
            if (targetIndex < 0) {
                e.setDropCompleted(false);
                return;
            }
            session.reorderTrack(movedId, targetIndex);
            e.setDropCompleted(true);
            e.consume();
        });
    }

    private WritableImage renderDragImage() {
        try {
            var params = new SnapshotParameters();
            return snapshot(params, null);
        } catch (Exception ignore) {
            return null;
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String displayChannel(SequenceChannel ch) {
        if (ch == null) return "";
        return ch.subIndex() == 0 ? ch.sourceName() : ch.sourceName() + "[" + ch.subIndex() + "]";
    }

    private static String describeChannel(SequenceChannel ch) {
        if (ch == null) return "—";
        return displayChannel(ch);
    }

    private static String toRgba(Color c) {
        return String.format("rgba(%d, %d, %d, %.2f)",
            (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255), c.getOpacity());
    }

    // ── Context menu (TimelineContextMenuContributor) ───────────────────────

    @Override
    public java.util.List<javafx.scene.control.MenuItem> menuItems() {
        var t = track.get();
        if (t == null) return java.util.List.of();
        var V = ax.xz.mri.ui.menu.ContextMenuVocabulary.class;
        var items = new java.util.ArrayList<javafx.scene.control.MenuItem>();

        items.add(ax.xz.mri.ui.menu.ContextMenuVocabulary.RENAME.item(() -> nameField.requestFocus()));

        boolean isCollapsed = session.isTrackCollapsed(t.id());
        var collapse = new javafx.scene.control.MenuItem(isCollapsed ? "Expand" : "Collapse");
        collapse.setOnAction(e -> session.setTrackCollapsed(t.id(), !isCollapsed));
        items.add(collapse);

        items.add(ax.xz.mri.ui.menu.ContextMenuVocabulary.separator());
        items.add(ax.xz.mri.ui.menu.ContextMenuVocabulary.DUPLICATE.item(() -> session.duplicateTrack(t.id())));
        items.add(ax.xz.mri.ui.menu.ContextMenuVocabulary.DELETE.item  (() -> session.removeTrack(t.id())));
        items.add(ax.xz.mri.ui.menu.ContextMenuVocabulary.separator());

        var clear = new javafx.scene.control.MenuItem("Clear All Clips");
        clear.setOnAction(e -> {
            var ids = session.clips.stream()
                .filter(c -> t.id().equals(c.trackId()))
                .map(c -> c.id())
                .toList();
            ids.forEach(session::removeClip);
        });
        items.add(clear);

        return items;
    }
}
