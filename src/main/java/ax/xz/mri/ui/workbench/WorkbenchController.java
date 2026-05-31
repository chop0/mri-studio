package ax.xz.mri.ui.workbench;

import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.ui.sim.SimDispatcher;
import ax.xz.mri.ui.viewmodel.StudioSession;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import ax.xz.mri.ui.workbench.pane.ExplorerPane;
import ax.xz.mri.ui.inspector.InspectorPane;
import ax.xz.mri.ui.workbench.pane.MessagesPane;
import ax.xz.mri.ui.workbench.pane.PointsWorkbenchPane;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.BorderPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import software.coley.bentofx.Bento;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.layout.container.DockContainerLeaf;
import software.coley.bentofx.layout.container.DockContainerRootBranch;

import java.io.File;
import java.util.EnumMap;
import java.util.Map;

/**
 * Workbench controller with BentoFX document tabs and tool windows.
 *
 * <p>Each open document (sequence, import, sim config) gets a {@link WorkspaceTab}
 * with a {@link DocumentEditorProvider}. Document tabs live in a BentoFX leaf at the
 * top of the centre area. Analysis tool windows below re-point to the active tab's data.
 */
public class WorkbenchController {
    private static final int STUDIO_DRAG_GROUP = 1;

    private final StudioSession session;
    private final CommandRegistry commandRegistry = new CommandRegistry();
    private final StringProperty shellStatus = new SimpleStringProperty("Ready");
    /**
     * Status segments rendered by the shell with vertical {@link
     * javafx.scene.control.Separator}s between them. Project convention is
     * to never bake a unicode separator into the status string; this property
     * is the structured replacement.
     */
    private final javafx.beans.property.ObjectProperty<java.util.List<String>> shellStatusSegments =
        new javafx.beans.property.SimpleObjectProperty<>(java.util.List.of("Ready"));
    private final BorderPane dockContainer = new BorderPane();

    // Sidebar singletons (Explorer, Inspector, Messages, Points). Analysis
    // panes (Sphere/Cross-section/Phase Maps/Traces) are owned per-document
    // by SequenceEditorPane; editors are owned per-document by their providers.
    private final Map<PaneId, WorkbenchPane> panes = new EnumMap<>(PaneId.class);
    private final Map<PaneId, Dockable> dockables = new EnumMap<>(PaneId.class);
    private final Map<PaneId, String> paneStatuses = new EnumMap<>(PaneId.class);

    // Document tabs
    private final ObservableList<WorkspaceTab> openTabs = FXCollections.observableArrayList();
    private final ObjectProperty<WorkspaceTab> activeTab = new SimpleObjectProperty<>();

    // Sidebars
    private final ToolSidebar leftSidebar = new ToolSidebar(ToolSidebar.Side.LEFT, 220);
    private final ToolSidebar rightSidebar = new ToolSidebar(ToolSidebar.Side.RIGHT, 300);

    // Dock bar — minimised tabs (currently always empty since there are no
    // analysis-pane dockables; auto-hides via MinimizeBar).
    private final MinimizeBar dockBar = new MinimizeBar(this::restorePane);

    // BentoFX layout
    private Bento bento;
    private DockContainerRootBranch rootBranch;
    private DockContainerLeaf documentLeaf; // the only leaf — holds every open document tab
    private Stage mainStage;
    private boolean disposed;
    private boolean switchingTabs;

    public WorkbenchController(StudioSession session) {
        this.session = session;
        initializePanes();
        initializeSidebars();
        registerCommands();
        installShellStatusBindings();
        installWorkspaceSwitching();
    }

    public void initialize(Stage stage) {
        this.mainStage = stage;
        loadLayoutFromStore();
    }

    // --- Public accessors ---

    public Node dockRoot() { return dockContainer; }
    /** Test-only accessor for asserting Bento drag/drop setup. */
    public Bento bentoForTesting() { return bento; }
    public ToolSidebar leftSidebar() { return leftSidebar; }
    public ToolSidebar rightSidebar() { return rightSidebar; }
    public StringProperty shellStatusProperty() { return shellStatus; }
    public javafx.beans.property.ObjectProperty<java.util.List<String>> shellStatusSegmentsProperty() {
        return shellStatusSegments;
    }
    public java.util.List<String> shellStatusSegments() { return shellStatusSegments.get(); }
    public CommandRegistry commandRegistry() { return commandRegistry; }
    public StudioSession session() { return session; }
    public ObjectProperty<WorkspaceTab> activeTabProperty() { return activeTab; }

    // --- Tab lifecycle ---

    /** Open a document in a workspace tab. Reuses existing tab if already open. */
    public void openTab(String id, String name, DocumentEditorProvider editor) {
        // Check if already open
        for (var tab : openTabs) {
            if (tab.id().equals(id)) {
                documentLeaf.selectDockable(tab.dockable());
                return;
            }
        }

        var tab = new WorkspaceTab(id, name, editor);
        var editorNode = editor.editorContent();

        // Focus detection: when this editor gains focus, make it the active tab
        // and show a subtle focus ring
        editorNode.focusedProperty().addListener((obs, o, focused) -> {
            if (focused && activeTab.get() != tab && !switchingTabs) {
                switchToTab(tab);
            }
        });
        editorNode.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (activeTab.get() != tab && !switchingTabs) {
                switchToTab(tab);
            }
        });

        var dockable = bento.dockBuilding().dockable("doc-" + id + "-" + System.nanoTime());
        dockable.setTitle(name);
        dockable.setNode(editorNode);
        dockable.setClosable(true);
        dockable.setCanBeDragged(true);
        // Full BentoFX drag/drop: tabs can be torn off into floating windows
        // or dropped onto the sides of the document leaf to split it.
        dockable.setCanBeDroppedToNewWindow(true);
        dockable.setDragGroup(STUDIO_DRAG_GROUP);
        dockable.setContextMenuFactory(ignored -> buildDocumentTabMenu(tab));
        tab.setDockable(dockable);

        // Wire title updates: when the editor's dirty state changes, update the dockable title.
        // This is the SINGLE place where all title wiring happens — not in individual providers.
        Runnable refreshTitle = () -> dockable.setTitle(tab.displayName());
        if (editor instanceof SequenceEditorProvider seq) {
            seq.editorPane.setOnTitleChanged(refreshTitle);
        }
        if (editor instanceof SimConfigEditorProvider) {
            var pane = ((SimConfigEditorProvider) editor).editorPane();
            if (pane != null) pane.setOnTitleChanged(refreshTitle);
        }
        if (editor instanceof EigenfieldEditorProvider eigen) {
            var pane = eigen.editorPane();
            if (pane != null) pane.setOnTitleChanged(refreshTitle);
        }

        openTabs.add(tab);
        documentLeaf.addDockable(dockable);
        documentLeaf.selectDockable(dockable);
    }

    /** Open a sequence as a workspace tab. */
    public void openSequenceTab(SequenceDocument document) {
        openTab(document.id().value(), document.name(),
            new SequenceEditorProvider(document, session, this));
    }

    /** Open a sim config as a workspace tab. */
    public void openSimConfigTab(SimulationConfigDocument configDoc) {
        openTab(configDoc.id().value(), configDoc.name(),
            new SimConfigEditorProvider(configDoc, session, this));
    }

    /** Open a hardware config as a workspace tab. */
    public void openHardwareConfigTab(ax.xz.mri.project.HardwareConfigDocument configDoc) {
        openTab(configDoc.id().value(), configDoc.name(),
            new HardwareConfigEditorProvider(configDoc, session, this));
    }

    /** Open an eigenfield as a workspace tab. */
    public void openEigenfieldTab(EigenfieldDocument eigenfield) {
        openTab(eigenfield.id().value(), eigenfield.name(),
            new EigenfieldEditorProvider(eigenfield, session, this));
    }

    /** Switch to a different tab, saving/restoring state. */
    private void switchToTab(WorkspaceTab newTab) {
        if (switchingTabs || newTab == null) return;
        switchingTabs = true;
        try {
            var oldTab = activeTab.get();

            // Save outgoing state
            if (oldTab != null) {
                oldTab.setSnapshot(oldTab.editor().captureState(session));
            }

            activeTab.set(newTab);

            newTab.editor().activate(session);
            if (newTab.snapshot() != null) {
                newTab.editor().restoreState(session, newTab.snapshot());
            } else {
                var simulation = session.document.simulation.get();
                if (simulation != null) {
                    double halfZ = 0;
                    for (var s : simulation.substances()) {
                        halfZ = Math.max(halfZ, s.halfExtent().z());
                    }
                    if (halfZ <= 0) halfZ = 1e-3;
                    session.geometry.fitVisibleRange(-halfZ * 1e3, halfZ * 1e3);
                }
            }

            // Update dockable title (dirty indicator)
            if (newTab.dockable() != null) {
                newTab.dockable().setTitle(newTab.displayName());
            }

            // Focus ring: remove from all, add to active
            for (var tab : openTabs) {
                tab.editor().editorContent().getStyleClass().remove("editor-focus-ring");
            }
            newTab.editor().editorContent().getStyleClass().add("editor-focus-ring");
        } finally {
            switchingTabs = false;
        }
        updateShellStatus();
    }

    /** Close a workspace tab. Autosave keeps every edit on disk; no save prompt. */
    public void closeTab(WorkspaceTab tab) {
        tab.editor().dispose();
        if (tab.dockable() != null) documentLeaf.removeDockable(tab.dockable());
        openTabs.remove(tab);

        if (activeTab.get() == tab) {
            if (!openTabs.isEmpty()) {
                documentLeaf.selectDockable(openTabs.getLast().dockable());
            } else {
                activeTab.set(null);
                session.activeEditSession.set(null);
            }
        }
    }

    /**
     * Find the live {@link SimConfigEditorProvider} for an open sim config
     * tab, if any. Used by the inspector's "Show in schematic" affordance to
     * push a highlight overlay onto the schematic canvas of an already-open
     * (or freshly-opened) tab.
     */
    public java.util.Optional<SimConfigEditorProvider> findSimConfigEditor(
            ax.xz.mri.project.ProjectNodeId simConfigId) {
        if (simConfigId == null) return java.util.Optional.empty();
        for (var tab : openTabs) {
            if (tab.id().equals(simConfigId.value())
                    && tab.editor() instanceof SimConfigEditorProvider provider) {
                return java.util.Optional.of(provider);
            }
        }
        return java.util.Optional.empty();
    }

    /** Get all open simulation sessions (for pushing config updates). */
    public java.util.Collection<SimDispatcher> allSimSessions() {
        return openTabs.stream()
            .map(WorkspaceTab::editor)
            .filter(SequenceEditorProvider.class::isInstance)
            .map(e -> ((SequenceEditorProvider) e).simSession)
            .toList();
    }

    /** Look up the {@link SimDispatcher} for the given edit session, or null. */
    public SimDispatcher simSessionFor(ax.xz.mri.ui.edit.EditSession editSession) {
        for (var tab : openTabs) {
            if (tab.editor() instanceof SequenceEditorProvider sep
                && sep.editSession == editSession) {
                return sep.simSession;
            }
        }
        return null;
    }

    /** Get all open hardware run sessions (for the inspector's Run-on-Hardware button). */
    public java.util.Collection<ax.xz.mri.ui.viewmodel.HardwareRunSession> allHardwareSessions() {
        return openTabs.stream()
            .map(WorkspaceTab::editor)
            .filter(SequenceEditorProvider.class::isInstance)
            .map(e -> ((SequenceEditorProvider) e).hardwareSession)
            .toList();
    }

    // --- Close / Restore (dock bar) ---

    /**
     * Close a dockable pane (best-effort). Sidebar panes ignore this; dockable
     * doc tabs use the tab's close button instead.
     */
    public void closePane(PaneId paneId) {
        var dockable = dockables.get(paneId);
        if (dockable == null || dockBar.isMinimized(paneId)) return;
        dockable.inContainer(c -> c.removeDockable(dockable));
        dockBar.addPane(paneId, paneId.title());
    }

    /** Restore a closed pane (re-add to the document leaf as a fallback). */
    public void restorePane(PaneId paneId) {
        var dockable = dockables.get(paneId);
        if (dockable == null || !dockBar.isMinimized(paneId)) return;
        dockBar.removePane(paneId);
        if (documentLeaf != null) {
            documentLeaf.addDockable(dockable);
            documentLeaf.selectDockable(dockable);
        }
    }

    public boolean isPaneClosed(PaneId paneId) { return dockBar.isMinimized(paneId); }
    public MinimizeBar dockBar() { return dockBar; }

    // --- Pane management ---

    public void activatePane(PaneId paneId) {
        session.docking.activate(paneId);
        var dockable = dockables.get(paneId);
        if (dockable != null) dockable.inContainer(c -> c.selectDockable(dockable));
        updateShellStatus();
    }

    public void setPaneStatus(PaneId paneId, String text) {
        paneStatuses.put(paneId, text == null ? "" : text);
        updateShellStatus();
    }

    public boolean isFloating(PaneId paneId) {
        var pane = panes.get(paneId);
        if (pane == null || pane.getScene() == null || mainStage == null || mainStage.getScene() == null) return false;
        return pane.getScene() != mainStage.getScene();
    }

    public void focusPane(PaneId paneId) {
        // Analysis sub-tabs (Sphere, Cross-section, Phase / Polar / Magnitude
        // traces) live inside the active document tab's SequenceEditorPane,
        // not in Bento's workbench layout. For those, ask the editor to
        // select the matching JavaFX Tab. Bento-registered panes (Explorer,
        // Inspector, Points, Messages) fall through to activatePane.
        if (delegateToActiveSequenceEditor(paneId)) return;
        activatePane(paneId);
        var pane = panes.get(paneId);
        if (pane != null && pane.getScene() != null && pane.getScene().getWindow() instanceof Stage s) {
            s.toFront(); s.requestFocus();
        }
        if (pane != null) pane.requestFocus();
    }

    /**
     * If {@code paneId} is one of the in-document analysis tabs and the active
     * document tab is a sequence editor, ask the editor to select that tab.
     * Returns true when the focus was handled there.
     */
    private boolean delegateToActiveSequenceEditor(PaneId paneId) {
        if (paneId != PaneId.SPHERE && paneId != PaneId.CROSS_SECTION
            && paneId != PaneId.TRACE_PHASE && paneId != PaneId.TRACE_POLAR
            && paneId != PaneId.TRACE_MAGNITUDE) {
            return false;
        }
        var tab = activeTab.get();
        if (tab == null) return false;
        var editor = tab.editor();
        if (editor instanceof SequenceEditorProvider seq && seq.editorPane != null) {
            return seq.editorPane.selectAnalysisTab(paneId);
        }
        return false;
    }

    public void floatPane(PaneId paneId) {
        if (mainStage == null || mainStage.getScene() == null) return;
        var dockable = dockables.get(paneId);
        if (dockable == null) return;
        if (isFloating(paneId)) { focusPane(paneId); return; }
        bento.stageBuilding().newStageForDockable(mainStage.getScene(), dockable,
            mainStage.getX() + 90, mainStage.getY() + 90);
        focusPane(paneId);
    }

    public void dockPane(PaneId paneId) {
        // If the pane is in the dock bar (closed), restore it first
        if (dockBar.isMinimized(paneId)) {
            restorePane(paneId);
            return;
        }
        var d = dockables.get(paneId);
        if (d == null) return;
        // Best-effort: if the dockable is detached, re-add to the document leaf.
        if (d.getContainer() == null && documentLeaf != null) {
            documentLeaf.addDockable(d);
        }
        if (d.getContainer() != null) d.getContainer().selectDockable(d);
        focusPane(paneId);
    }

    public void resetLayout() { rebuildWorkbench(); }

    /**
     * Layout is now structurally fixed (one document leaf hosting every open
     * tab). There is nothing to persist, so save/load are no-ops that
     * regenerate the fixed structure. The methods stay as public hooks for
     * the View menu — clicking "Reset Layout" still does the right thing.
     */
    public void loadLayoutFromStore() { rebuildWorkbench(); }
    public void saveLayoutToStore()   { /* nothing to save — structure is fixed */ }

    private Dockable createDockable(software.coley.bentofx.building.DockBuilding builder, PaneId paneId) {
        var pane = panes.get(paneId);
        if (pane == null) return null;
        var dockable = builder.dockable(paneId.name());
        dockable.setTitle(paneId.title());
        dockable.setNode(pane);
        dockable.setClosable(false);
        dockable.setCanBeDragged(true);
        dockable.setCanBeDroppedToNewWindow(true);
        dockable.setDragGroup(STUDIO_DRAG_GROUP);
        dockable.setContextMenuFactory(ignored -> buildToolWindowMenu(paneId));
        dockables.put(paneId, dockable);
        return dockable;
    }

    // --- File operations ---

    public void openProjectChooser() {
        var chooser = new DirectoryChooser();
        chooser.setTitle("Open Project");
        File dir = chooser.showDialog(mainStage);
        if (dir == null) return;
        openProjectDirectory(dir);
    }

    public void openProjectDirectory(File dir) {
        try { session.project.openProject(dir.toPath()); updateShellStatus(); }
        catch (Exception ex) { showError("Failed to open project", ex.getMessage()); }
    }

    /** Always returns true — autosave guarantees every edit is on disk. */
    public boolean confirmCloseAllEditors() { return true; }

    /**
     * Cmd+Z dispatched against the currently focused editor's scope. When
     * no document tab is open the undo log is filtered to STRUCTURAL
     * mutations only, so explorer-level Ctrl+Z reverts add/remove/rename
     * but not in-document content edits buried deeper in history.
     */
    public void undoContextual() {
        session.state.undoIn(focusedScopeFilter());
    }

    public void redoContextual() {
        session.state.redoIn(focusedScopeFilter());
    }

    private java.util.function.Predicate<ax.xz.mri.state.Mutation> focusedScopeFilter() {
        var tab = activeTab.get();
        if (tab == null) return session.state.structural();
        return session.state.any();
    }

    /**
     * Cmd+S forces an immediate flush of any pending autosave debounce. The
     * mutation log is already authoritative; this just hurries the disk write.
     */
    public void saveContextual() {
        session.state.flush();
        updateShellStatus();
    }

    public void saveProject() {
        try {
            var root = session.project.projectRoot.get();
            if (root == null) saveProjectAsChooser();
            else { session.project.saveProject(root); updateShellStatus(); }
        } catch (Exception ex) { showError("Failed to save project", ex.getMessage()); }
    }

    public void saveProjectAsChooser() {
        var chooser = new DirectoryChooser();
        chooser.setTitle("Save Project As");
        File dir = chooser.showDialog(mainStage);
        if (dir == null) return;
        try { session.project.saveProject(dir.toPath()); updateShellStatus(); }
        catch (Exception ex) { showError("Failed to save project", ex.getMessage()); }
    }

    public void dispose() {
        if (disposed) return;
        disposed = true;
        for (var tab : openTabs) tab.editor().dispose();
        panes.values().forEach(WorkbenchPane::dispose);
    }

    public void populateWindowMenu(Menu menu) {
        menu.getItems().clear();
        menu.getItems().addAll(
            menuItem("Float Active Pane", CommandId.FLOAT_ACTIVE_PANE),
            menuItem("Dock Active Pane", CommandId.DOCK_ACTIVE_PANE),
            menuItem("Focus Active Pane", CommandId.FOCUS_ACTIVE_PANE),
            new SeparatorMenuItem()
        );
        for (var paneId : PaneId.values()) {
            // Skip sidebar tools, per-doc editors, and non-BentoFX panes
            if (paneId == PaneId.EXPLORER || paneId == PaneId.INSPECTOR
                || paneId == PaneId.SEQUENCE_EDITOR || paneId == PaneId.SIM_CONFIG_EDITOR
                || paneId == PaneId.EIGENFIELD_EDITOR
                || paneId == PaneId.POINTS
                || paneId == PaneId.MESSAGES) continue;
            var focus = new MenuItem("Focus " + paneId.title());
            focus.setOnAction(e -> focusPane(paneId));
            menu.getItems().add(focus);
        }
    }

    public Node buildMainToolStrip() {
        var contextLabel = new Label();
        var computeStatus = new Label();
        session.derived.computing.addListener((obs, o, n) ->
            computeStatus.setText(n ? "Computing\u2026" : ""));

        Runnable refresh = () -> {
            var tab = activeTab.get();
            var repo = session.state.current();
            if (tab != null) contextLabel.setText(tab.displayName());
            else contextLabel.setText(repo.manifest().name());
        };
        refresh.run();
        activeTab.addListener((obs, o, n) -> refresh.run());
        session.state.currentProperty().addListener((obs, o, n) -> refresh.run());

        var simStatus = new javafx.scene.control.ProgressIndicator(-1);
        simStatus.setPrefSize(14, 14); simStatus.setMaxSize(14, 14);
        simStatus.setVisible(false); simStatus.setStyle("-fx-progress-color: #e06000;");
        new javafx.animation.AnimationTimer() {
            @Override public void handle(long now) {
                simStatus.setVisible(allSimSessions().stream()
                    .anyMatch(s -> s.state.get() instanceof ax.xz.mri.ui.sim.SimState.Running));
            }
        }.start();

        var spacer = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        var bar = new javafx.scene.layout.HBox(8, contextLabel, spacer, simStatus, computeStatus);
        bar.getStyleClass().add("shell-tool-strip");
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        bar.setPadding(new javafx.geometry.Insets(4, 6, 4, 6));
        return bar;
    }

    // --- Initialization ---

    /**
     * Build the four sidebar singletons: Explorer / Inspector / Messages /
     * Points. Analysis panes (Sphere / Cross-section / Phase Maps / Traces)
     * are constructed by {@link ax.xz.mri.ui.pane.SequenceEditorPane} per-
     * document — they don't exist outside an open sequence editor.
     */
    private void initializePanes() {
        for (var paneId : java.util.List.of(
                PaneId.EXPLORER, PaneId.INSPECTOR, PaneId.MESSAGES, PaneId.POINTS)) {
            panes.put(paneId, createPane(paneId));
        }
    }

    private void initializeSidebars() {
        // Left sidebar: Explorer
        leftSidebar.addTool(new ToolSidebar.Tool("explorer",
            "Explorer", StudioIcons.create(StudioIconKind.PROJECT), panes.get(PaneId.EXPLORER)));
        leftSidebar.showTool("explorer"); // open by default

        // Right sidebar: Inspector, Points, Messages. Points is a sibling
        // of Inspector here so users can flip between editing a clip and
        // viewing the isochromat list without losing screen space.
        rightSidebar.addTool(new ToolSidebar.Tool("inspector",
            "Inspector", StudioIcons.create(StudioIconKind.SIMULATION), panes.get(PaneId.INSPECTOR)));
        rightSidebar.addTool(new ToolSidebar.Tool("points",
            "Points", StudioIcons.create(StudioIconKind.SIMULATION), panes.get(PaneId.POINTS)));
        rightSidebar.addTool(new ToolSidebar.Tool("messages",
            "Messages", StudioIcons.create(StudioIconKind.MESSAGES), panes.get(PaneId.MESSAGES)));
        rightSidebar.showTool("inspector"); // open by default

        // When an error lands in the message log, surface the Messages tool so the user sees it.
        session.messages.messages().addListener((javafx.collections.ListChangeListener<ax.xz.mri.ui.viewmodel.MessagesViewModel.Message>) change -> {
            while (change.next()) {
                if (!change.wasAdded()) continue;
                for (var msg : change.getAddedSubList()) {
                    if (msg.level() == ax.xz.mri.ui.viewmodel.MessagesViewModel.Level.ERROR) {
                        rightSidebar.showTool("messages");
                        return;
                    }
                }
            }
        });
    }

    private WorkbenchPane createPane(PaneId paneId) {
        var ctx = new PaneContext(session, this, paneId);
        return switch (paneId) {
            case EXPLORER  -> new ExplorerPane(ctx);
            case INSPECTOR -> new InspectorPane(ctx);
            case POINTS    -> new PointsWorkbenchPane(ctx);
            case MESSAGES  -> new MessagesPane(ctx);
            default -> throw new IllegalStateException(
                "WorkbenchController only constructs sidebar singletons; analysis "
                + "panes are owned by SequenceEditorPane and editors are per-document. "
                + "paneId=" + paneId);
        };
    }

    private void registerCommands() {
        commandRegistry.register(new PaneAction(CommandId.OPEN_PROJECT, "Open Project\u2026", this::openProjectChooser));
        commandRegistry.register(new PaneAction(CommandId.SAVE_PROJECT, "Save Project", this::saveProject));
        commandRegistry.register(new PaneAction(CommandId.SAVE_PROJECT_AS, "Save Project As\u2026", this::saveProjectAsChooser));
        commandRegistry.register(new PaneAction(CommandId.RESET_LAYOUT, "Reset Layout", this::resetLayout));
        commandRegistry.register(new PaneAction(CommandId.SAVE_LAYOUT, "Save Layout", this::saveLayoutToStore));
        commandRegistry.register(new PaneAction(CommandId.LOAD_LAYOUT, "Load Layout", this::loadLayoutFromStore));
        commandRegistry.register(new PaneAction(CommandId.FLOAT_ACTIVE_PANE, "Float Active Pane", () -> {
            var a = session.docking.activePaneId.get(); if (a != null) floatPane(a);
        }));
        commandRegistry.register(new PaneAction(CommandId.DOCK_ACTIVE_PANE, "Dock Active Pane", () -> {
            var a = session.docking.activePaneId.get(); if (a != null) dockPane(a);
        }));
        commandRegistry.register(new PaneAction(CommandId.FOCUS_ACTIVE_PANE, "Focus Active Pane", () -> {
            var a = session.docking.activePaneId.get(); if (a != null) focusPane(a);
        }));
        commandRegistry.register(new PaneAction(CommandId.RESET_POINTS, "Reset Points", session.points::resetToDefaults));
        commandRegistry.register(new PaneAction(CommandId.CLEAR_USER_POINTS, "Clear User Points", session.points::clearUserPoints));
        commandRegistry.register(new PaneAction(CommandId.DELETE_SEQUENCE, "Delete Sequence", () -> {
            var n = session.project.inspector.inspectedNodeId.get();
            if (n != null && session.state.current().node(n) instanceof SequenceDocument)
                session.project.deleteSequence(n);
        }));
        commandRegistry.register(new PaneAction(CommandId.NEW_SIM_CONFIG, "New Sim Config", this::newSimConfigWizard));
        commandRegistry.register(new PaneAction(CommandId.NEW_HARDWARE_CONFIG, "New Hardware Config", this::newHardwareConfigWizard));
        commandRegistry.register(new PaneAction(CommandId.NEW_EIGENFIELD, "New Eigenfield", this::newEigenfieldWizard));
        commandRegistry.register(new PaneAction(CommandId.NEW_SEQUENCE, "New Sequence", this::newSequenceWizard));
        commandRegistry.register(new PaneAction(CommandId.NEW_SUBSTANCE, "New Substance", this::newSubstanceWizard));
        commandRegistry.register(new PaneAction(CommandId.DELETE_SUBSTANCE, "Delete Substance", () -> {
            var n = session.project.inspector.inspectedNodeId.get();
            if (n != null && session.state.current().node(n) instanceof ax.xz.mri.project.SubstanceDocument)
                session.project.deleteSubstance(n);
        }));
        commandRegistry.register(new PaneAction(CommandId.NEW_PROCEDURE, "New Procedure", this::newProcedureWizard));
        commandRegistry.register(new PaneAction(CommandId.DELETE_PROCEDURE, "Delete Procedure", () -> {
            var n = session.project.inspector.inspectedNodeId.get();
            if (n != null && session.state.current().node(n) instanceof ax.xz.mri.project.ProcedureDocument)
                session.project.deleteProcedure(n);
        }));
    }

    private void newProcedureWizard() {
        ax.xz.mri.ui.wizard.NewProcedureWizard.show(mainStage, session.project).ifPresent(doc -> {
            session.project.selectNode(doc.id());
            openProcedureTab(doc);
        });
    }

    /** Open a procedure as a workspace tab. */
    public void openProcedureTab(ax.xz.mri.project.ProcedureDocument doc) {
        openTab(doc.id().value(), doc.name(),
            new ProcedureEditorProvider(doc, session, this));
    }

    private void newSimConfigWizard() {
        ax.xz.mri.ui.wizard.NewSimConfigWizard.show(mainStage, session.project).ifPresent(doc -> {
            session.project.selectNode(doc.id());
            openSimConfigTab(doc);
        });
    }

    private void newHardwareConfigWizard() {
        ax.xz.mri.ui.wizard.NewHardwareConfigWizard.show(mainStage, session.project).ifPresent(doc -> {
            session.project.selectNode(doc.id());
            openHardwareConfigTab(doc);
        });
    }

    private void newEigenfieldWizard() {
        ax.xz.mri.ui.wizard.NewEigenfieldWizard.show(mainStage, session.project).ifPresent(ef -> {
            session.project.selectNode(ef.id());
            openEigenfieldTab(ef);
        });
    }

    private void newSequenceWizard() {
        ax.xz.mri.ui.wizard.NewSequenceWizard.show(mainStage, session.project).ifPresent(seq -> {
            session.project.selectNode(seq.id());
            openSequenceTab(seq);
        });
    }

    private void newSubstanceWizard() {
        ax.xz.mri.ui.wizard.NewSubstanceWizard.show(mainStage, session.project).ifPresent(doc -> {
            session.project.selectNode(doc.id());
            openSubstanceTab(doc);
        });
    }

    /** Open a substance as a workspace tab. */
    public void openSubstanceTab(ax.xz.mri.project.SubstanceDocument doc) {
        openTab(doc.id().value(), doc.name(),
            new SubstanceEditorProvider(doc, session, this));
    }

    private void installShellStatusBindings() {
        session.timeAxis.cursor.time.addListener((obs, o, n) -> updateShellStatus());
        session.points.entries.addListener((javafx.collections.ListChangeListener<ax.xz.mri.ui.model.IsochromatEntry>) c ->
            updateShellStatus());
    }

    private void installWorkspaceSwitching() {
        session.project.setOnSequenceOpened(this::openSequenceTab);
        session.project.setOnSimConfigOpened(this::openSimConfigTab);
        session.project.setOnHardwareConfigOpened(this::openHardwareConfigTab);
        session.project.setOnEigenfieldOpened(this::openEigenfieldTab);
        session.project.setOnSubstanceOpened(this::openSubstanceTab);
        session.project.setOnProcedureOpened(this::openProcedureTab);
    }

    // --- BentoFX layout ---

    /**
     * Build the dock layout: a single document leaf that hosts every open
     * document tab (sequences, hardware configs, sim configs, eigenfields).
     *
     * <p>Each tab fills the dock area on its own. Document-specific chrome
     * (the analysis tile + DAW for a sequence, the schematic editor for a
     * hardware config, etc.) lives <em>inside</em> the editor pane, not in
     * the workbench. This means: opening a hardware-config tab shows just
     * the hardware-config editor — no analysis panes leak through.
     */
    private void rebuildWorkbench() {
        if (bento != null && rootBranch != null) bento.unregisterRoot(rootBranch);
        bento = new Bento();
        configureBento();
        dockables.clear();

        var builder = bento.dockBuilding();
        var root = builder.root("studio-root");

        var docLeaf = builder.leaf("document_tabs");
        docLeaf.setPruneWhenEmpty(false);
        // Allow drops on the sides of the leaf to split the dock area into
        // two leaves (and recursively). This is BentoFX's native split-on-
        // drop — users drag a tab out and dock it left/right/above/below to
        // create a new pane next to the original.
        docLeaf.setCanSplit(true);
        root.addContainers(docLeaf);

        // CRITICAL: registerRoot tells Bento to consider this root for drag/drop
        // target detection. Without it, getRootContainers() is empty, every
        // drag has no targets, and drops fail silently — which is exactly the
        // "can't drag tabs" symptom users hit. DockBuilding.root(name) only
        // constructs the branch; it does not auto-register.
        bento.registerRoot(root);

        rootBranch = root;
        documentLeaf = docLeaf;

        dockContainer.setCenter(rootBranch);
        dockContainer.setBottom(dockBar); // auto-hides when empty
    }

    /** Context menu for document editor tabs. */
    private javafx.scene.control.ContextMenu buildDocumentTabMenu(WorkspaceTab tab) {
        var menu = new javafx.scene.control.ContextMenu();
        var closeItem = new MenuItem("Close");
        closeItem.setOnAction(e -> closeTab(tab));
        var closeOthers = new MenuItem("Close Others");
        closeOthers.setOnAction(e -> {
            for (var other : java.util.List.copyOf(openTabs)) {
                if (other != tab) closeTab(other);
            }
        });
        var closeAll = new MenuItem("Close All");
        closeAll.setOnAction(e -> {
            for (var t : java.util.List.copyOf(openTabs)) closeTab(t);
        });
        menu.getItems().addAll(closeItem, closeOthers, closeAll);
        return menu;
    }

    /** Context menu for analysis tool window tabs. */
    private javafx.scene.control.ContextMenu buildToolWindowMenu(PaneId paneId) {
        var menu = new javafx.scene.control.ContextMenu();
        var closeItem = new MenuItem("Close");
        closeItem.setOnAction(e -> closePane(paneId));
        var floatItem = new MenuItem(isFloating(paneId) ? "Dock to Default" : "Float");
        floatItem.setOnAction(e -> {
            if (isFloating(paneId)) dockPane(paneId); else floatPane(paneId);
        });
        var dockItem = new MenuItem("Restore to Default Position");
        dockItem.setOnAction(e -> dockPane(paneId));
        menu.getItems().addAll(closeItem, new SeparatorMenuItem(), floatItem, dockItem);
        return menu;
    }

    private void configureBento() {
        bento.stageBuilding().setSceneFactory(this::createDockingScene);
        bento.events().addDockableSelectListener((path, dockable) -> {
            // Check if it's a document tab
            for (var tab : openTabs) {
                if (tab.dockable() == dockable) {
                    if (activeTab.get() != tab && !switchingTabs) switchToTab(tab);
                    return;
                }
            }
            // Otherwise it's an analysis pane
            var paneId = paneIdOf(dockable);
            if (paneId != null) { session.docking.activate(paneId); updateShellStatus(); }
        });
        bento.events().addDockableCloseListener((closePath, closedDockable) -> {
            for (var tab : java.util.List.copyOf(openTabs)) {
                if (tab.dockable() == closedDockable) {
                    closeTab(tab);
                    return;
                }
            }
        });
        // (No need for DockableParentChanged listener — close/restore uses home leaves,
        // and contextual hide/show uses dividers without mutating the tree.)
    }

    private Scene createDockingScene(Scene src, javafx.scene.layout.Region region, double w, double h) {
        var scene = new Scene(region, w, h);
        if (src != null) scene.getStylesheets().setAll(src.getStylesheets());
        return scene;
    }

    /**
     * Set divider positions, deferring to after the scene is attached if needed.
     */
    private static void deferDividers(software.coley.bentofx.layout.container.DockContainerBranch branch, double... positions) {
        branch.setDividerPositions(positions);
        javafx.application.Platform.runLater(() -> branch.setDividerPositions(positions));
    }

    private PaneId paneIdOf(Dockable dockable) {
        try { return dockable == null ? null : PaneId.valueOf(dockable.getIdentifier()); }
        catch (IllegalArgumentException ex) { return null; }
    }

    private void updateShellStatus() {
        var tab = activeTab.get();
        String tabName = tab != null ? tab.displayName() : "\u2014";
        long visible = session.points.entries.stream().filter(ax.xz.mri.ui.model.IsochromatEntry::visible).count();
        var segments = java.util.List.of(
            "Tab: " + tabName,
            String.format("Cursor: %.1f \u03bcs", session.timeAxis.cursor.time.get()),
            String.format("Points: %d (%d visible)", session.points.entries.size(), visible)
        );
        shellStatusSegments.set(segments);
        // Keep the legacy joined-text property in sync for any callers still
        // bound to it (mostly tests + tooltip read-outs).
        shellStatus.set(String.join("   ", segments));
    }

    private void showError(String title, String message) {
        var alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title); alert.setHeaderText(title); alert.setContentText(message);
        alert.showAndWait();
    }

    private MenuItem menuItem(String label, CommandId id) {
        var item = new MenuItem(label);
        item.setOnAction(e -> commandRegistry.execute(id));
        return item;
    }
}
