package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.hardware.HardwarePluginRegistry;
import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.project.HardwareConfigDocument;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.ui.viewmodel.HardwareRunSession;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import ax.xz.mri.ui.viewmodel.SequenceSimulationSession;
import ax.xz.mri.ui.viewmodel.TimelineViewportController;
import ax.xz.mri.ui.viewmodel.ZoomDirection;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.StudioIconKind;
import ax.xz.mri.ui.workbench.StudioIcons;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import ax.xz.mri.ui.workbench.pane.timeline.TimelineCanvas;
import ax.xz.mri.ui.workbench.pane.timeline.TimelineOverviewBar;
import ax.xz.mri.util.SiFormat;
import ax.xz.mri.state.Mutation;
import ax.xz.mri.state.Scope;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.collections.ObservableSet;
import javafx.collections.SetChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.cell.CheckBoxTreeCell;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.LinkedHashSet;
import java.util.Set;
import java.time.Instant;

/**
 * Root pane for the clip-based sequence editor ("DAW").
 *
 * <p>Layout (top to bottom):
 * toolbar (tools, undo/redo, zoom, outputs, save) → overview bar →
 * timeline canvas (editable tracks + read-only output rows) → status strip.
 *
 * <p>Hardware-run controls live in the inspector's "Hardware" section, not in
 * this toolbar — see {@link InspectorPane}.
 */
public final class SequenceEditorPane extends WorkbenchPane {

    private final SequenceEditSession editSession = new SequenceEditSession();
    public SequenceEditSession editSession() { return editSession; }

    private final SequenceToolPalette toolPalette;
    private final TimelineOverviewBar overviewBar;
    private final TimelineCanvas trackCanvas;
    private final TimelineViewportController viewportController;

    private SequenceSimulationSession simSession;
    private HardwareRunSession hardwareSession;
    private Runnable onTitleChanged;
    private String sequenceName = "";

    public SequenceEditorPane(PaneContext paneContext) {
        super(paneContext);
        setPaneTitle("Sequence Editor");

        var viewport = paneContext.session().viewport;
        viewportController = paneContext.session().timeline.viewportController;
        editSession.setViewport(viewport);

        toolPalette  = new SequenceToolPalette(editSession);
        overviewBar  = new TimelineOverviewBar(editSession, viewport, viewportController);
        trackCanvas  = new TimelineCanvas(editSession, viewport);

        toolPalette.setOnActiveToolChanged(() ->
            trackCanvas.setActiveCreationKind(toolPalette.activeClipKind())
        );

        editSession.setRepositorySupplier(() -> paneContext.session().state.current());
        editSession.setStateManager(paneContext.session().state);
        editSession.revision.addListener((obs, o, n) -> notifyTitleChanged());

        var root = new BorderPane();
        root.getStyleClass().add("sequence-editor");
        root.setTop(buildToolbar());
        root.setCenter(buildCentre());
        root.setBottom(buildStatusStrip(paneContext));
        setPaneContent(root);

        wireKeyboardShortcuts(root);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Toolbar (tools, undo/redo, zoom, outputs, save)
    // ══════════════════════════════════════════════════════════════════════════

    private HBox buildToolbar() {
        var undoBtn = iconButton(StudioIconKind.UNDO, "Undo (⌘Z)", editSession::undo);
        undoBtn.disableProperty().bind(editSession.canUndoProperty().not());

        var redoBtn = iconButton(StudioIconKind.REDO, "Redo (⌘⇧Z)", editSession::redo);
        redoBtn.disableProperty().bind(editSession.canRedoProperty().not());

        var zoomOutBtn = new Button("-");
        zoomOutBtn.setTooltip(new Tooltip("Zoom out"));
        zoomOutBtn.setOnAction(e -> viewportController.zoomViewportAt(viewCentre(), ZoomDirection.OUT));
        zoomOutBtn.setFocusTraversable(false);
        zoomOutBtn.getStyleClass().add("seq-toolbar-button");

        var zoomInBtn = new Button("+");
        zoomInBtn.setTooltip(new Tooltip("Zoom in"));
        zoomInBtn.setOnAction(e -> viewportController.zoomViewportAt(viewCentre(), ZoomDirection.IN));
        zoomInBtn.setFocusTraversable(false);
        zoomInBtn.getStyleClass().add("seq-toolbar-button");

        var fitBtn = new Button("Fit");
        fitBtn.setTooltip(new Tooltip("Zoom to fit (⌘F)"));
        fitBtn.setOnAction(e -> viewportController.fitViewportToData());
        fitBtn.setFocusTraversable(false);
        fitBtn.getStyleClass().add("seq-toolbar-button");

        var saveBtn = new Button("Save");
        saveBtn.setGraphic(StudioIcons.create(StudioIconKind.SAVE));
        saveBtn.setTooltip(new Tooltip("Save (⌘S)"));
        saveBtn.setOnAction(e -> saveSequence());
        saveBtn.setFocusTraversable(false);
        saveBtn.getStyleClass().addAll("seq-toolbar-button", "primary");

        var outputsBtn = buildOutputsPopover();

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var toolbar = new HBox(4,
            toolPalette,
            verticalSeparator(),
            undoBtn, redoBtn,
            verticalSeparator(),
            zoomOutBtn, zoomInBtn, fitBtn,
            verticalSeparator(),
            outputsBtn,
            spacer,
            saveBtn
        );
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(3, 6, 3, 4));
        toolbar.getStyleClass().add("seq-toolbar");
        return toolbar;
    }

    /**
     * Tree-select popover that drives which probe rows are rendered beneath
     * the editable tracks. The tree groups per-probe checkboxes by run
     * context: a "Simulation" parent toggles every sim probe, "Hardware"
     * toggles every hardware probe, individual leaves toggle a single probe.
     * State is bound directly to {@link SequenceEditSession#enabledSimOutputs}
     * and {@link SequenceEditSession#enabledHardwareOutputs}; toggling a leaf
     * triggers an immediate canvas repaint via the session's set listeners.
     */
    private MenuButton buildOutputsPopover() {
        var btn = new MenuButton("Outputs");
        btn.setGraphic(StudioIcons.create(StudioIconKind.OUTPUTS));
        btn.setFocusTraversable(false);
        btn.getStyleClass().add("seq-toolbar-button");
        btn.setTooltip(new Tooltip("Show / hide read-only run output rows"));

        var tree = new TreeView<OutputTreeNode>();
        tree.setShowRoot(false);
        // Custom cell factory: leaves are CheckBoxTreeItem (rendered with a
        // checkbox); branches without children carry a plain TreeItem holding
        // a hint, which we render as muted italic text without a checkbox.
        tree.setCellFactory(tv -> new javafx.scene.control.cell.CheckBoxTreeCell<OutputTreeNode>(
            item -> {
                if (item instanceof CheckBoxTreeItem<OutputTreeNode> cb) return cb.selectedProperty();
                return null;
            },
            new StringConverter<>() {
                @Override public String toString(TreeItem<OutputTreeNode> item) {
                    return item == null || item.getValue() == null ? "" : item.getValue().label();
                }
                @Override public TreeItem<OutputTreeNode> fromString(String s) { return null; }
            }
        ) {
            @Override
            public void updateItem(OutputTreeNode item, boolean empty) {
                super.updateItem(item, empty);
                var ti = getTreeItem();
                if (item != null && ti != null && item.hint() && !(ti instanceof CheckBoxTreeItem<?>)) {
                    setGraphic(null);
                    setText(item.label());
                    setStyle("-fx-text-fill: #7a8290; -fx-font-style: italic;");
                    setDisable(true);
                } else {
                    setStyle("");
                    setDisable(false);
                }
            }
        });
        tree.setPrefSize(220, 200);
        tree.setRoot(buildOutputsTree());
        // Rebuild the tree when the underlying probe set might change.
        editSession.activeConfig.addListener((obs, o, n) -> tree.setRoot(buildOutputsTree()));
        editSession.activeHardwareConfigId.addListener((obs, o, n) -> tree.setRoot(buildOutputsTree()));
        editSession.lastSimulationTraces.addListener((obs, o, n) -> tree.setRoot(buildOutputsTree()));
        editSession.lastHardwareTraces.addListener((obs, o, n) -> tree.setRoot(buildOutputsTree()));

        var item = new CustomMenuItem(tree);
        item.setHideOnClick(false);
        btn.getItems().add(item);
        return btn;
    }

    /** Build the two-level tree: Simulation [...] / Hardware [...]. */
    private TreeItem<OutputTreeNode> buildOutputsTree() {
        var root = new TreeItem<>(new OutputTreeNode("Outputs", false));

        var simNames = collectSimProbeNames();
        if (!simNames.isEmpty()) root.getChildren().add(makeGroup("Simulation",
            simNames, editSession.enabledSimOutputs));
        else root.getChildren().add(emptyBranch("Simulation", "No probes in active config"));

        var hwNames = collectHardwareProbeNames();
        if (!hwNames.isEmpty()) root.getChildren().add(makeGroup("Hardware",
            hwNames, editSession.enabledHardwareOutputs));
        else root.getChildren().add(emptyBranch("Hardware", "No hardware config bound"));

        return root;
    }

    private CheckBoxTreeItem<OutputTreeNode> makeGroup(String groupLabel,
                                                       Set<String> probeNames,
                                                       ObservableSet<String> backingSet) {
        var group = new CheckBoxTreeItem<>(new OutputTreeNode(groupLabel, false));
        group.setExpanded(true);
        for (var name : probeNames) {
            var leaf = new CheckBoxTreeItem<>(new OutputTreeNode(name, false));
            leaf.setSelected(backingSet.contains(name));
            // Two-way bind: leaf checkbox <-> backingSet membership.
            leaf.selectedProperty().addListener((obs, o, n) -> {
                if (Boolean.TRUE.equals(n)) backingSet.add(name);
                else backingSet.remove(name);
            });
            backingSet.addListener((SetChangeListener<String>) c -> {
                boolean shouldBeSelected = backingSet.contains(name);
                if (leaf.isSelected() != shouldBeSelected) leaf.setSelected(shouldBeSelected);
            });
            group.getChildren().add(leaf);
        }
        return group;
    }

    /**
     * Branch for a context that has no probes available — the single child is
     * a plain {@link TreeItem} (not {@link CheckBoxTreeItem}) carrying a hint;
     * the cell factory renders it as italic muted text without a checkbox.
     */
    private TreeItem<OutputTreeNode> emptyBranch(String groupLabel, String hint) {
        var group = new TreeItem<>(new OutputTreeNode(groupLabel, false));
        group.setExpanded(true);
        group.getChildren().add(new TreeItem<>(new OutputTreeNode(hint, true)));
        return group;
    }

    private Set<String> collectSimProbeNames() {
        var out = new LinkedHashSet<String>();
        var circuit = editSession.activeCircuit();
        if (circuit != null) {
            for (CircuitComponent.Probe p : circuit.probes()) out.add(p.name());
        }
        // Include probe names from the trace map even if the active circuit
        // doesn't list them (stale traces from a previous config).
        var traces = editSession.lastSimulationTraces.get();
        if (traces != null) out.addAll(traces.byProbe().keySet());
        return out;
    }

    private Set<String> collectHardwareProbeNames() {
        var out = new LinkedHashSet<String>();
        HardwareConfigDocument hwConfig = editSession.activeHardwareConfigDoc();
        if (hwConfig != null && hwConfig.config() != null) {
            HardwarePluginRegistry.byId(hwConfig.config().pluginId()).ifPresent(plugin ->
                out.addAll(plugin.capabilities().probeNames()));
        }
        var traces = editSession.lastHardwareTraces.get();
        if (traces != null) out.addAll(traces.byProbe().keySet());
        return out;
    }

    /**
     * Internal value type for the Outputs tree — {@code hint} marks an
     * informational placeholder (e.g. "No hardware config bound") so the
     * cell factory can render it as italic muted text without a checkbox.
     */
    private record OutputTreeNode(String label, boolean hint) {}

    private Button iconButton(StudioIconKind kind, String tooltip, Runnable action) {
        var btn = new Button();
        btn.setGraphic(StudioIcons.create(kind));
        btn.setTooltip(new Tooltip(tooltip));
        btn.setOnAction(e -> action.run());
        btn.setFocusTraversable(false);
        btn.getStyleClass().add("seq-toolbar-button");
        return btn;
    }

    private Separator verticalSeparator() {
        var sep = new Separator(javafx.geometry.Orientation.VERTICAL);
        sep.setPadding(new Insets(2, 4, 2, 4));
        return sep;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Centre (overview + track canvas)
    // ══════════════════════════════════════════════════════════════════════════

    private VBox buildCentre() {
        var overviewHolder = new StackPane(overviewBar);
        overviewHolder.setMinHeight(40);
        overviewHolder.setPrefHeight(40);
        overviewHolder.setMaxHeight(40);
        overviewHolder.getStyleClass().add("seq-overview-holder");

        var canvasHolder = new StackPane(trackCanvas);
        canvasHolder.getStyleClass().add("seq-canvas-holder");
        VBox.setVgrow(canvasHolder, Priority.ALWAYS);

        return new VBox(overviewHolder, canvasHolder);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Status strip (selection + timing readouts)
    // ══════════════════════════════════════════════════════════════════════════

    private HBox buildStatusStrip(PaneContext paneContext) {
        var selection = new Label();
        selection.getStyleClass().add("seq-status-selection");
        Runnable updateSel = () -> {
            int n = editSession.selectedClipIds.size();
            selection.setText(n == 0 ? "No selection" : (n == 1 ? "1 clip selected" : n + " clips selected"));
        };
        updateSel.run();
        editSession.selectedClipIds.addListener((SetChangeListener<String>) c -> updateSel.run());

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var strip = new HBox(10,
            selection,
            spacer,
            statItem("zoom",   zoomBinding()),
            statItem("span",   viewSpanBinding()),
            statItem("dt",     dtBinding()),
            statItem("cursor", cursorBinding(paneContext)),
            statItem("total",  totalDurationBinding())
        );
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.setPadding(new Insets(3, 10, 3, 10));
        strip.getStyleClass().add("seq-status-strip");
        return strip;
    }

    private HBox statItem(String label, StringBinding value) {
        var lbl = new Label(label.toUpperCase());
        lbl.getStyleClass().add("seq-status-label");
        var val = new Label();
        val.getStyleClass().add("seq-status-value");
        val.textProperty().bind(value);
        var row = new HBox(4, lbl, val);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private StringBinding viewSpanBinding() {
        var viewport = paneContext.session().viewport;
        return Bindings.createStringBinding(
            () -> SiFormat.time(viewport.vE.get() - viewport.vS.get()),
            viewport.vS, viewport.vE);
    }

    /** Mid-point of the visible viewport — pivot for cursor-less zoom (toolbar buttons, key shortcuts). */
    private double viewCentre() {
        var viewport = paneContext.session().viewport;
        return (viewport.vS.get() + viewport.vE.get()) / 2;
    }

    /** Multiplier of fit-to-data zoom — "1×" means the whole sequence is visible, "10×" means we're 10× zoomed in. */
    private StringBinding zoomBinding() {
        var viewport = paneContext.session().viewport;
        return Bindings.createStringBinding(() -> {
            double span = viewport.vE.get() - viewport.vS.get();
            if (span <= 0) return "—";
            double total = Math.max(1, viewport.maxTime.get());
            double zoom = total / span;
            return zoom < 10 ? String.format("%.1f×", zoom) : String.format("%.0f×", zoom);
        }, viewport.vS, viewport.vE, viewport.maxTime);
    }

    private StringBinding dtBinding() {
        return Bindings.createStringBinding(() -> SiFormat.time(editSession.dt.get()), editSession.dt);
    }

    private StringBinding totalDurationBinding() {
        return Bindings.createStringBinding(
            () -> SiFormat.time(editSession.totalDuration.get()), editSession.totalDuration);
    }

    private StringBinding cursorBinding(PaneContext paneContext) {
        var viewport = paneContext.session().viewport;
        return Bindings.createStringBinding(() -> SiFormat.time(viewport.tC.get()), viewport.tC);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Keyboard shortcuts
    // ══════════════════════════════════════════════════════════════════════════

    private void wireKeyboardShortcuts(BorderPane root) {
        root.setOnKeyPressed(event -> {
            if (new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN).match(event)) {
                saveSequence(); event.consume();
            } else if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN).match(event)) {
                editSession.redo(); event.consume();
            } else if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN).match(event)) {
                editSession.undo(); event.consume();
            } else if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                editSession.deleteSelectedClips(); event.consume();
            } else if (new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN).match(event)) {
                editSession.duplicateSelectedClips(); event.consume();
            } else if (new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN).match(event)) {
                editSession.selectAll(); event.consume();
            } else if (new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN).match(event)) {
                viewportController.fitViewportToData(); event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                toolPalette.activeTool.set(SequenceToolKind.SELECT);
                editSession.clearSelection();
                event.consume();
            }
        });
        root.setFocusTraversable(true);
        trackCanvas.setOnMouseClicked(e -> root.requestFocus());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // External API
    // ══════════════════════════════════════════════════════════════════════════

    /** Wire the simulation session (called by WorkbenchController after open). */
    public void wireSimSession(SequenceSimulationSession session) {
        this.simSession = session;
        editSession.applyActiveConfig(session.activeConfig.get());
        session.activeConfig.addListener((obs, o, n) -> editSession.applyActiveConfig(n));
    }

    /** Wire the hardware run session (called once after construction). */
    public void wireHardwareSession(HardwareRunSession session) {
        this.hardwareSession = session;
    }

    /** Resolved hardware run session, if wired. Used by the inspector to surface the Run button. */
    public HardwareRunSession hardwareSession() { return hardwareSession; }

    public void setOnTitleChanged(Runnable callback) { this.onTitleChanged = callback; }

    public String tabTitle() { return sequenceName; }

    public void open(SequenceDocument document) {
        editSession.open(document);
        sequenceName = document.name();
        paneContext.session().activeEditSession.set(editSession);
        notifyTitleChanged();
    }

    private void notifyTitleChanged() {
        if (onTitleChanged != null) onTitleChanged.run();
    }

    private void saveSequence() {
        var updated = editSession.toDocument();
        var currentConfigId = updated.activeSimConfigId();
        var state = paneContext.session().state;
        var existing = state.current().sequence(updated.id());
        var scope = Scope.indexed(Scope.root(), "sequences", updated.id());
        state.dispatch(new Mutation(scope, existing, updated,
            "Save sequence", Instant.now(), "sequence-editor",
            existing == null
                ? Mutation.Category.STRUCTURAL
                : Mutation.Category.CONTENT));
        editSession.open(updated);
        if (currentConfigId != null) editSession.setOriginalSimConfigId(currentConfigId);
        paneContext.session().activeEditSession.set(editSession);
        paneContext.session().project.explorer.refresh();
        notifyTitleChanged();
    }

    @Override
    public void dispose() {
        overviewBar.dispose();
        trackCanvas.dispose();
    }
}
