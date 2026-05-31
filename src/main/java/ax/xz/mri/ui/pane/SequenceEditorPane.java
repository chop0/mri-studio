package ax.xz.mri.ui.pane;

import ax.xz.mri.model.sequence.ClipKind;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.state.Mutation;
import ax.xz.mri.state.Scope;
import ax.xz.mri.ui.edit.EditSession;
import ax.xz.mri.ui.edit.SelectionContext;
import ax.xz.mri.ui.sim.SimDispatcher;
import ax.xz.mri.ui.sim.SimState;
import ax.xz.mri.ui.time.TimeAxis;
import ax.xz.mri.ui.timeline.TimelineRoot;
import ax.xz.mri.ui.viewmodel.HardwareRunSession;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.PaneId;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import ax.xz.mri.ui.workbench.pane.GeometryPane;
import ax.xz.mri.ui.workbench.pane.MagnitudeTracePane;
import ax.xz.mri.ui.workbench.pane.PhaseTracePane;
import ax.xz.mri.ui.workbench.pane.PolarTracePane;
import ax.xz.mri.ui.workbench.pane.SphereWorkbenchPane;
import ax.xz.mri.hardware.HardwarePluginRegistry;
import ax.xz.mri.ui.widget.CommandButton;
import ax.xz.mri.ui.widget.CommandPopupButton;
import ax.xz.mri.ui.widget.CommandRibbon;
import ax.xz.mri.ui.widget.CommandToggle;
import ax.xz.mri.ui.widget.StudioIcons;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ObservableSet;
import javafx.collections.SetChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.geometry.Orientation;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.util.StringConverter;
import org.controlsfx.control.NotificationPane;
import org.controlsfx.control.StatusBar;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Root pane for the clip-based sequence editor.
 *
 * <p>This is the rebuild's surface for everything the user sees while editing
 * a sequence. The plan-driven composition is, top-to-bottom:
 * <pre>
 *   ┌─ EditorToolbar ───────────────────────────────────────────────────┐
 *   │ tool palette (SegmentedButton) │ undo/redo │ zoom │ outputs │ ⚙   │
 *   ├─ NotificationPane wrapping… ──────────────────────────────────────┤
 *   │   TimelineRoot (time-axis ribbon → lane stack → SplitPane →        │
 *   │                 output band → overview bar)                        │
 *   ├─ EditorStatusBar ─────────────────────────────────────────────────┤
 *   │ sim state pill │ cursor readout │ progress │ messages              │
 *   └────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Every chrome control here uses ControlsFX where it earns its keep:
 * {@link SegmentedButton} for the tool palette, {@link ToggleSwitch} for the
 * snap and auto-follow toggles, {@link StatusBar} along the bottom,
 * {@link NotificationPane} wrapping the timeline so simulation errors slide
 * in non-modally. Stock JavaFX widgets cover the rest.
 */
public final class SequenceEditorPane extends WorkbenchPane {

    private final EditSession editSession = new EditSession();
    private final TimelineRoot timelineRoot;
    private final NotificationPane notifications;
    private final StatusBar statusBar = new StatusBar();
    private final SimpleObjectProperty<ClipKind> activeCreationKind = new SimpleObjectProperty<>(null);
    private final TimeAxis timeAxis;

    private SimDispatcher simSession;
    private HardwareRunSession hardwareSession;
    private Runnable onTitleChanged;
    private String sequenceName = "";

    public SequenceEditorPane(PaneContext paneContext) {
        super(paneContext);
        setPaneTitle("Sequence Editor");

        timeAxis = paneContext.session().timeAxis;
        editSession.setTimeAxis(timeAxis);
        editSession.setRepositorySupplier(() -> paneContext.session().state.current());
        editSession.setStateManager(paneContext.session().state);
        editSession.revision.addListener((obs, o, n) -> notifyTitleChanged());

        timelineRoot = new TimelineRoot(editSession, timeAxis);
        timelineRoot.setActiveCreationKind(activeCreationKind::get);

        // Both viewport-mini-strip and DAW main strip are owned by
        // TimelineRoot now (top of its BorderPane). No separate bottom
        // overview bar — the mini-strip above the DAW serves the same
        // "set viewport bounds" role with a clearer mental model.
        notifications = new NotificationPane(timelineRoot);
        notifications.setShowFromTop(true);
        notifications.setCloseButtonVisible(true);

        // Sequence-only chrome — analysis tile (Sphere/Cross-section + Maps/
        // Traces) lives INSIDE the sequence editor, not at the workbench level.
        // Only sequences need these views; opening a hw or sim config tab
        // shows only the doc-specific editor.
        var analysisTile = buildAnalysisTile(paneContext);
        var workspace = new SplitPane(analysisTile, notifications);
        workspace.setOrientation(Orientation.VERTICAL);
        workspace.setDividerPositions(0.45);
        SplitPane.setResizableWithParent(analysisTile, true);

        var root = new BorderPane();
        root.getStyleClass().add("sequence-editor");
        root.setTop(buildToolbar());
        root.setCenter(workspace);
        root.setBottom(buildStatusBar(paneContext));
        setPaneContent(root);

        wireKeyboardShortcuts(root);
        loadStylesheets(root);
    }

    /**
     * Two side-by-side TabPanes — left holds spatial views, right holds
     * the trace/map plots. Each tab is a freshly constructed analysis pane
     * scoped to this sequence editor; tabs are GC'd along with this pane
     * when the document tab is closed.
     */
    private Node buildAnalysisTile(PaneContext ctx) {
        var sphereTab = tab("Bloch Sphere",  new SphereWorkbenchPane(child(ctx, PaneId.SPHERE)));
        var crossTab  = tab("Cross-section", new GeometryPane         (child(ctx, PaneId.CROSS_SECTION)));
        var phaseTab  = tab("Phase Trace",   new PhaseTracePane    (child(ctx, PaneId.TRACE_PHASE)));
        var polarTab  = tab("Polar Trace",   new PolarTracePane    (child(ctx, PaneId.TRACE_POLAR)));
        var magTab    = tab("Magnitude",     new MagnitudeTracePane(child(ctx, PaneId.TRACE_MAGNITUDE)));

        analysisTabsByPane.put(PaneId.SPHERE,          sphereTab);
        analysisTabsByPane.put(PaneId.CROSS_SECTION,   crossTab);
        analysisTabsByPane.put(PaneId.TRACE_PHASE,     phaseTab);
        analysisTabsByPane.put(PaneId.TRACE_POLAR,     polarTab);
        analysisTabsByPane.put(PaneId.TRACE_MAGNITUDE, magTab);

        var spatial = new TabPane(sphereTab, crossTab);
        var traces  = new TabPane(phaseTab, polarTab, magTab);
        spatial.getStyleClass().add("seq-analysis-tabs");
        traces .getStyleClass().add("seq-analysis-tabs");
        spatial.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        traces .setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        var split = new SplitPane(spatial, traces);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.40);
        return split;
    }

    private final java.util.EnumMap<PaneId, Tab> analysisTabsByPane = new java.util.EnumMap<>(PaneId.class);

    /**
     * Activate the analysis sub-tab matching {@code paneId}, if this editor
     * hosts it. Returns {@code true} on success — the controller's
     * {@link ax.xz.mri.ui.workbench.WorkbenchController#focusPane} delegates
     * here for non-Bento panes (Sphere, Cross-section, traces).
     */
    public boolean selectAnalysisTab(PaneId paneId) {
        var tab = analysisTabsByPane.get(paneId);
        if (tab == null || tab.getTabPane() == null) return false;
        tab.getTabPane().getSelectionModel().select(tab);
        return true;
    }

    private static Tab tab(String title, WorkbenchPane pane) {
        var t = new Tab(title, pane);
        t.setClosable(false);
        return t;
    }

    private static PaneContext child(PaneContext parent, PaneId id) {
        return new PaneContext(parent.session(), parent.controller(), id);
    }

    public EditSession editSession() { return editSession; }
    public HardwareRunSession hardwareSession() { return hardwareSession; }
    public String tabTitle() { return sequenceName; }
    public void setOnTitleChanged(Runnable callback) { this.onTitleChanged = callback; }

    public void wireSimSession(SimDispatcher session) {
        this.simSession = session;
        session.state.addListener((obs, o, n) -> {
            if (n instanceof SimState.Failed f) notifications.show("Simulation failed: " + f.message());
        });
        rebindSimStateLabel();
        for (var hook : autoRebindHooks) hook.run();
    }

    public void wireHardwareSession(HardwareRunSession session) {
        this.hardwareSession = session;
    }

    public void open(SequenceDocument document) {
        editSession.open(document);
        sequenceName = document.name();
        paneContext.session().activeEditSession.set(editSession);
        notifyTitleChanged();
    }

    public void dispose() {
        // Children are scene-graph nodes; their listeners are GCed with them.
    }

    // ── Command Manager-style toolbar ───────────────────────────────────────
    //
    // SolidWorks Command Manager idiom: ribbons of icon-only command buttons
    // grouped under small-caps captions. The previous HBox-with-separators
    // toolbar was scattered and amateur-looking; this groups commands by
    // function (Tools / Edit / View / Run / Save) so the eye finds them fast.

    private Node buildToolbar() {
        var ribbon = new CommandRibbon();
        ribbon.addGroup(new CommandRibbon.Group("Tools",   buildToolGroup()));
        ribbon.addGroup(new CommandRibbon.Group("Edit",    buildEditGroup()));
        ribbon.addGroup(new CommandRibbon.Group("View",    buildViewGroup()));
        ribbon.addGroup(new CommandRibbon.Group("Run",     buildRunGroup()));
        ribbon.addSpacer();
        ribbon.addGroup(new CommandRibbon.Group("",        buildSaveGroup()));
        ribbon.finalizeLayout();
        return ribbon;
    }

    /** Tool-palette group: pictorial command toggles for clip kinds. */
    private HBox buildToolGroup() {
        var group = new ToggleGroup();
        var row = new HBox(1);
        row.setAlignment(Pos.CENTER);

        // Each tool: a CommandToggle with our bespoke 20×20 SVG icon. The
        // payload (ClipKind) is stored on userData; null for the Select tool.
        var select = paletteCmd(null,             StudioIcons.Kind.SELECT,    "Select", group);
        select.setSelected(true);
        row.getChildren().addAll(
            select,
            paletteCmd(ClipKind.SINE,      StudioIcons.Kind.SINE,      "Sine",       group),
            paletteCmd(ClipKind.SINC,      StudioIcons.Kind.SINC,      "Sinc",       group),
            paletteCmd(ClipKind.TRAPEZOID, StudioIcons.Kind.TRAPEZOID, "Trapezoid",  group),
            paletteCmd(ClipKind.GAUSSIAN,  StudioIcons.Kind.GAUSSIAN,  "Gaussian",   group),
            paletteCmd(ClipKind.TRIANGLE,  StudioIcons.Kind.TRIANGLE,  "Triangle",   group),
            paletteCmd(ClipKind.CONSTANT,  StudioIcons.Kind.CONSTANT,  "Constant",   group),
            paletteCmd(ClipKind.SPLINE,    StudioIcons.Kind.SPLINE,    "Spline",     group)
        );
        // Re-select the previous toggle if the user clicks the active one
        // (otherwise tool "deselects" and there's no active tool).
        group.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n == null) {
                if (o != null) ((ToggleButton) o).setSelected(true);
                return;
            }
            activeCreationKind.set((ClipKind) n.getUserData());
        });
        return row;
    }

    private CommandToggle paletteCmd(ClipKind kind, StudioIcons.Kind iconKind, String tooltip, ToggleGroup group) {
        var t = new CommandToggle(StudioIcons.of(iconKind), tooltip);
        t.setUserData(kind);
        t.setToggleGroup(group);
        return t;
    }

    /** Edit group: undo / redo + snap toggle. */
    private HBox buildEditGroup() {
        var undoBtn = CommandButton.of(StudioIcons.Kind.UNDO, "Undo (⌘Z)");
        undoBtn.setOnAction(e -> editSession.undo());
        undoBtn.disableProperty().bind(editSession.canUndoProperty().not());

        var redoBtn = CommandButton.of(StudioIcons.Kind.REDO, "Redo (⌘⇧Z)");
        redoBtn.setOnAction(e -> editSession.redo());
        redoBtn.disableProperty().bind(editSession.canRedoProperty().not());

        // Snap is an editing affordance — it controls whether clip edges
        // align to the grid during drag/resize. Belongs with undo/redo,
        // not under "Run" (which is for sim/hardware execution).
        var snapBtn = CommandToggle.of(StudioIcons.Kind.SNAP, "Snap clip edges to neighbours and the grid");
        snapBtn.selectedProperty().bindBidirectional(editSession.snap.enabled);

        var row = new HBox(1, undoBtn, redoBtn, snapBtn);
        row.setAlignment(Pos.CENTER);
        return row;
    }

    /** View group: zoom in / out / fit. */
    private HBox buildViewGroup() {
        var zoomOutBtn = CommandButton.of(StudioIcons.Kind.ZOOM_OUT, "Zoom out");
        zoomOutBtn.setOnAction(e -> timeAxis.viewport.zoomAround(viewportCentre(), TimeAxis.ZOOM_OUT_FACTOR));

        var zoomInBtn = CommandButton.of(StudioIcons.Kind.ZOOM_IN, "Zoom in");
        zoomInBtn.setOnAction(e -> timeAxis.viewport.zoomAround(viewportCentre(), TimeAxis.ZOOM_IN_FACTOR));

        var fitBtn = CommandButton.of(StudioIcons.Kind.ZOOM_FIT, "Zoom to fit (⌘F)");
        fitBtn.setOnAction(e -> timeAxis.viewport.fit());

        var row = new HBox(1, zoomOutBtn, zoomInBtn, fitBtn);
        row.setAlignment(Pos.CENTER);
        return row;
    }

    /** Run group: Run, Auto-re-run toggle, outputs popover. */
    private HBox buildRunGroup() {
        var runBtn = CommandButton.of(StudioIcons.Kind.PLAY, "Run simulation now");
        runBtn.setOnAction(e -> {
            if (simSession != null) simSession.simulate();
        });

        var autoBtn = CommandToggle.of(StudioIcons.Kind.AUTO_RUN,
            "Auto-run on every edit (debounced)");
        // Bind bidirectionally once the simSession is wired in. Re-bind whenever
        // wireSimSession() lands a new dispatcher.
        Runnable rebindAuto = () -> {
            autoBtn.selectedProperty().unbind();
            if (simSession != null) {
                autoBtn.selectedProperty().bindBidirectional(simSession.autoSimulate);
            }
        };
        rebindAuto.run();
        // wireSimSession is called after constructor returns; re-bind then too.
        // The simplest reliable hook is to re-run on every refresh trigger that
        // already exists. For now bind once — re-wiring is supported below.
        autoRebindHooks.add(rebindAuto);

        var outputsBtn = buildOutputsMenu();

        var row = new HBox(1, runBtn, autoBtn, outputsBtn);
        row.setAlignment(Pos.CENTER);
        return row;
    }

    /** Hooks fired in {@link #wireSimSession} to re-bind any UI tied to the dispatcher. */
    private final java.util.List<Runnable> autoRebindHooks = new java.util.ArrayList<>();

    /** Save group: primary-action save button. */
    private HBox buildSaveGroup() {
        var saveBtn = CommandButton.of(StudioIcons.Kind.SAVE, "Save sequence (⌘S)").primary();
        saveBtn.setOnAction(e -> saveSequence());
        var row = new HBox(saveBtn);
        row.setAlignment(Pos.CENTER);
        return row;
    }

    /**
     * Outputs popover — a two-level tree (Simulation / Hardware groups, each
     * containing per-probe checkboxes) inside a {@link MenuButton}'s popover.
     *
     * <p>The tree gives us nested visual grouping that a flat
     * {@link javafx.scene.control.CheckMenuItem} list can't: each context
     * (sim / hw) gets its own header that is itself a tristate checkbox, and
     * empty contexts can show a muted hint ("No hardware config bound") in
     * place of the missing probe leaves. Bound bidirectionally to the
     * {@link EditSession}'s observable enabled-output sets so toggling either
     * the leaf checkbox or the group checkbox updates the live render.
     */
    private CommandPopupButton buildOutputsMenu() {
        var btn = CommandPopupButton.of(StudioIcons.Kind.OUTPUTS, "Show / hide read-only probe rows");

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

    private CheckBoxTreeItem<OutputTreeNode> makeGroup(
        String groupLabel,
        Set<String> probeNames,
        ObservableSet<String> backingSet
    ) {
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
            for (var p : circuit.probes()) out.add(p.name());
            for (var c : circuit.opticalCounters()) out.add(c.name());
        }
        // Include probe names from the trace map even if the active circuit
        // doesn't list them (stale traces from a previous config).
        var traces = editSession.lastSimulationTraces.get();
        if (traces != null) out.addAll(traces.byProbe().keySet());
        return out;
    }

    private Set<String> collectHardwareProbeNames() {
        var out = new LinkedHashSet<String>();
        var hwConfig = editSession.activeHardwareConfigDoc();
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

    private double viewportCentre() {
        return (timeAxis.viewport.start.get() + timeAxis.viewport.end.get()) * 0.5;
    }

    // ── Status bar ───────────────────────────────────────────────────────────

    private final Label simStateLabel = new Label("—");

    private Node buildStatusBar(PaneContext paneContext) {
        statusBar.setText("");

        simStateLabel.getStyleClass().add("sim-state-pill");
        // Pin width so transitions between "Idle" / "Pending…" / "Running…"
        // don't reflow the status bar → tab → editor every state change.
        simStateLabel.setMinWidth(96);
        simStateLabel.setPrefWidth(96);

        var cursorReadout = new Label();
        cursorReadout.textProperty().bind(Bindings.createStringBinding(
            () -> "t = " + ax.xz.mri.util.SiFormat.time(timeAxis.cursor.time.get()),
            timeAxis.cursor.time));
        cursorReadout.getStyleClass().add("cursor-readout");
        // The formatted time string varies in width as the cursor moves between
        // ns/µs/ms regimes; pinning a generous width here stops every cursor
        // move from triggering a full status-bar relayout.
        cursorReadout.setMinWidth(140);
        cursorReadout.setPrefWidth(140);

        statusBar.getLeftItems().setAll(simStateLabel, new Separator(javafx.geometry.Orientation.VERTICAL), cursorReadout);
        statusBar.getStyleClass().add("editor-status-bar");
        return statusBar;
    }

    private void rebindSimStateLabel() {
        if (simSession == null) {
            simStateLabel.setText("—");
            return;
        }
        Runnable refresh = () -> simStateLabel.setText(switch (simSession.state.get()) {
            case SimState.Idle __    -> "Idle";
            case SimState.Pending __ -> "Pending…";
            case SimState.Running __ -> "Running…";
            case SimState.Failed f   -> "Failed: " + f.message();
            case null                -> "—";
        });
        simSession.state.addListener((obs, o, n) -> refresh.run());
        refresh.run();
    }

    // ── Keyboard shortcuts ───────────────────────────────────────────────────

    private void wireKeyboardShortcuts(BorderPane root) {
        // Generic cut/copy/paste/delete/duplicate/select-all wired through
        // the SelectionContext abstraction. Bubbling decides who handles the
        // event: a focused schematic-pane child consumes its own Cmd+V before
        // this handler ever sees it (assuming the schematic has a matching
        // ClipboardChannel format on the system clipboard).
        new SelectionContext<>(
            editSession.selection,
            EditSession.CLIP_CLIPBOARD,
            editSession::findClip,
            items -> editSession.pasteAtCursor(),
            ids -> editSession.deleteSelectedClips(),
            ids -> editSession.duplicateSelectedClips(),
            editSession::selectAllClips
        ).attachTo(root);

        // Editor-specific shortcuts that aren't in the selection vocabulary.
        var save = new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN);
        var undo = new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN);
        var redo = new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
        var fit  = new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN);
        root.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getTarget() instanceof javafx.scene.control.TextInputControl) return;
            if (save.match(e))      { saveSequence(); e.consume(); }
            else if (undo.match(e)) { editSession.undo(); e.consume(); }
            else if (redo.match(e)) { editSession.redo(); e.consume(); }
            else if (fit.match(e))  { timeAxis.viewport.fit(); e.consume(); }
            else if (e.getCode() == KeyCode.ESCAPE) { editSession.selection.clear(); e.consume(); }
        });
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    private void saveSequence() {
        var updated = editSession.toDocument();
        var currentConfigId = updated.activeSimConfigId();
        var state = paneContext.session().state;
        var existing = state.current().sequence(updated.id());
        var scope = Scope.indexed(Scope.root(), "sequences", updated.id());
        state.dispatch(new Mutation(scope, existing, updated,
            "Save sequence", Instant.now(), "sequence-editor",
            existing == null ? Mutation.Category.STRUCTURAL : Mutation.Category.CONTENT));
        editSession.open(updated);
        if (currentConfigId != null) editSession.setOriginalSimConfigId(currentConfigId);
        paneContext.session().activeEditSession.set(editSession);
        paneContext.session().project.explorer.refresh();
        notifyTitleChanged();
    }

    private void notifyTitleChanged() {
        if (onTitleChanged != null) onTitleChanged.run();
    }

    private void loadStylesheets(Region root) {
        root.getStylesheets().add(getClass().getResource("/ax/xz/mri/ui/timeline/timeline.css").toExternalForm());
        root.getStylesheets().add(getClass().getResource("/ax/xz/mri/ui/timeline/clip.css").toExternalForm());
        // ScrubStrip self-loads its own CSS via getUserAgentStylesheet().
    }
}
