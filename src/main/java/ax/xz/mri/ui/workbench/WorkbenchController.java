package ax.xz.mri.ui.workbench;

import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.ui.viewmodel.SequenceSimulationSession;
import ax.xz.mri.ui.viewmodel.StudioSession;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import ax.xz.mri.ui.workbench.pane.ExplorerPane;
import ax.xz.mri.ui.workbench.pane.GeometryPane;
import ax.xz.mri.ui.workbench.pane.InspectorPane;
import ax.xz.mri.ui.workbench.pane.MagnitudeTracePane;
import ax.xz.mri.ui.workbench.pane.MessagesPane;
import ax.xz.mri.ui.workbench.pane.PhaseMapRPane;
import ax.xz.mri.ui.workbench.pane.PhaseMapZPane;
import ax.xz.mri.ui.workbench.pane.PhaseTracePane;
import ax.xz.mri.ui.workbench.pane.PolarTracePane;
import ax.xz.mri.ui.workbench.pane.PointsWorkbenchPane;
import ax.xz.mri.ui.workbench.pane.SphereWorkbenchPane;
import ax.xz.mri.ui.workbench.pane.TimelineWorkbenchPane;
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

    /** Analysis pane IDs — contextually shown/hidden based on document type. */
    private static final PaneId[] ANALYSIS_PANE_IDS = {
        PaneId.CROSS_SECTION, PaneId.SPHERE,
        PaneId.PHASE_MAP_Z, PaneId.PHASE_MAP_R,
        PaneId.TRACE_PHASE, PaneId.TRACE_POLAR, PaneId.TRACE_MAGNITUDE,
        PaneId.TIMELINE
    };

    private final StudioSession session;
    private final CommandRegistry commandRegistry = new CommandRegistry();
    private final StringProperty shellStatus = new SimpleStringProperty("Ready");
    private final BorderPane dockContainer = new BorderPane();

    // Analysis panes — singletons, re-pointed on tab switch
    private final Map<PaneId, WorkbenchPane> panes = new EnumMap<>(PaneId.class);
    private final Map<PaneId, Dockable> dockables = new EnumMap<>(PaneId.class);
    private final Map<PaneId, DockContainerLeaf> homeLeaves = new EnumMap<>(PaneId.class);
    private final Map<PaneId, String> paneStatuses = new EnumMap<>(PaneId.class);

    // Document tabs
    private final ObservableList<WorkspaceTab> openTabs = FXCollections.observableArrayList();
    private final ObjectProperty<WorkspaceTab> activeTab = new SimpleObjectProperty<>();

    // Sidebars
    private final ToolSidebar leftSidebar = new ToolSidebar(ToolSidebar.Side.LEFT, 220);
    private final ToolSidebar rightSidebar = new ToolSidebar(ToolSidebar.Side.RIGHT, 300);

    // Dock bar — buttons for closed analysis panes (click to restore to home leaf)
    private final MinimizeBar dockBar = new MinimizeBar(this::restorePane);

    // BentoFX layout
    private Bento bento;
    private DockContainerRootBranch rootBranch;
    private software.coley.bentofx.layout.container.DockContainerBranch centreShellBranch;
    private DockContainerLeaf documentLeaf; // top — holds document tabs
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
    public ToolSidebar leftSidebar() { return leftSidebar; }
    public ToolSidebar rightSidebar() { return rightSidebar; }
    public StringProperty shellStatusProperty() { return shellStatus; }
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
        dockable.setCanBeDroppedToNewWindow(false);
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
                var data = session.document.simulationOutput.get();
                if (data != null && data.field() != null && data.field().zMm != null) {
                    session.geometry.fitVisibleRange(
                        data.field().zMm[0], data.field().zMm[data.field().zMm.length - 1]);
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

            // Contextual analysis area: hide via dividers when document has no analysis,
            // show when it does. The BentoFX tree is NEVER mutated — just divider positions.
            if (newTab.editor().relevantToolWindows().isEmpty()) {
                hideAnalysisArea();
            } else {
                showAnalysisArea();
            }
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
    public java.util.Collection<SequenceSimulationSession> allSimSessions() {
        return openTabs.stream()
            .map(WorkspaceTab::editor)
            .filter(SequenceEditorProvider.class::isInstance)
            .map(e -> ((SequenceEditorProvider) e).simSession)
            .toList();
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
     * Close a pane: remove from BentoFX tree, show a restore button in the dock bar.
     * The pane can be restored to its home leaf via the dock bar button or View menu.
     */
    public void closePane(PaneId paneId) {
        var dockable = dockables.get(paneId);
        if (dockable == null || dockBar.isMinimized(paneId)) return;
        dockable.inContainer(c -> c.removeDockable(dockable));
        dockBar.addPane(paneId, paneId.title());
    }

    /** Restore a closed pane back into its home leaf in the BentoFX tree. */
    public void restorePane(PaneId paneId) {
        var dockable = dockables.get(paneId);
        if (dockable == null || !dockBar.isMinimized(paneId)) return;
        dockBar.removePane(paneId);
        var home = homeLeaves.get(paneId);
        if (home != null && home.getParentContainer() != null) {
            home.addDockable(dockable);
            home.selectDockable(dockable);
        } else {
            // Home leaf was pruned — create a fresh one in the centre shell
            var freshLeaf = bento.dockBuilding().leaf(paneId.name().toLowerCase() + "-restored");
            freshLeaf.setPruneWhenEmpty(true);
            centreShellBranch.addContainer(freshLeaf);
            homeLeaves.put(paneId, freshLeaf);
            freshLeaf.addDockable(dockable);
            freshLeaf.selectDockable(dockable);
        }
    }

    public boolean isPaneClosed(PaneId paneId) { return dockBar.isMinimized(paneId); }
    public MinimizeBar dockBar() { return dockBar; }

    // --- Contextual analysis area visibility (divider-based, no tree mutation) ---

    /** Saved divider position when analysis area is visible. */
    private double savedCentreShellDivider = -1;

    /** Hide the bottom area (analysis + timeline) by pushing the centreShell divider to 1.0. */
    private void hideAnalysisArea() {
        if (centreShellBranch == null) return;
        var dividers = centreShellBranch.getDividerPositions();
        if (dividers.length > 0) savedCentreShellDivider = dividers[0];
        centreShellBranch.setDividerPositions(1.0);
    }

    /** Show the bottom area by restoring the centreShell divider. */
    private void showAnalysisArea() {
        if (centreShellBranch == null) return;
        centreShellBranch.setDividerPositions(savedCentreShellDivider > 0 ? savedCentreShellDivider : 0.45);
    }

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
        activatePane(paneId);
        var pane = panes.get(paneId);
        if (pane != null && pane.getScene() != null && pane.getScene().getWindow() instanceof Stage s) {
            s.toFront(); s.requestFocus();
        }
        if (pane != null) pane.requestFocus();
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
        var d = dockables.get(paneId); var hl = homeLeaves.get(paneId);
        if (d == null || hl == null) return;
        // If already in a leaf, move to home
        if (d.getContainer() != null && d.getContainer() != hl) {
            d.getContainer().removeDockable(d);
        }
        if (d.getContainer() == null) {
            hl.addDockable(d);
        }
        hl.selectDockable(d);
        focusPane(paneId);
    }

    public void resetLayout() { rebuildWorkbench(); }

    private static final java.nio.file.Path LAYOUT_FILE =
        java.nio.file.Path.of(System.getProperty("user.home"), ".mri-studio", "layout.json");
    private final PersistentLayoutStore layoutStore = new PersistentLayoutStore(LAYOUT_FILE);

    public void loadLayoutFromStore() {
        layoutStore.load().ifPresentOrElse(
            this::restoreLayout,
            this::rebuildWorkbench
        );
    }

    public void saveLayoutToStore() {
        try {
            layoutStore.save(captureLayout());
        } catch (Exception ex) {
            session.messages.logWarning("Layout", "Could not persist workbench layout: " + ex.getMessage());
        }
    }

    // --- Layout capture: live BentoFX tree → WorkbenchLayoutState ---

    private ax.xz.mri.ui.workbench.layout.WorkbenchLayoutState captureLayout() {
        // Capture the analysis/timeline portion of the tree (skip docLeaf — it's session-specific).
        ax.xz.mri.ui.workbench.layout.DockNode dockRoot = null;
        if (centreShellBranch != null && centreShellBranch.getChildContainers().size() > 1) {
            dockRoot = LayoutTreeIO.capture(centreShellBranch.getChildContainers().get(1), this::paneIdOf);
        }
        if (dockRoot == null) dockRoot = LayoutTreeIO.capture(rootBranch, this::paneIdOf);
        // Detect floating windows (dockables whose container's root != rootBranch)
        var floatingWindows = new java.util.ArrayList<ax.xz.mri.ui.workbench.layout.FloatingWindowState>();
        for (var entry : dockables.entrySet()) {
            var dockable = entry.getValue();
            if (dockable.getContainer() != null) {
                var scene = dockable.getContainer().getScene();
                if (scene != null && scene.getWindow() != mainStage) {
                    var w = scene.getWindow();
                    floatingWindows.add(new ax.xz.mri.ui.workbench.layout.FloatingWindowState(
                        entry.getKey(), w.getX(), w.getY(), w.getWidth(), w.getHeight()));
                }
            }
        }
        return new ax.xz.mri.ui.workbench.layout.WorkbenchLayoutState(dockRoot, floatingWindows);
    }

    // --- Layout restore: WorkbenchLayoutState → BentoFX tree ---

    private void restoreLayout(ax.xz.mri.ui.workbench.layout.WorkbenchLayoutState state) {
        if (state == null || state.dockRoot() == null) {
            rebuildWorkbench();
            return;
        }

        bento = new Bento();
        configureBento();
        dockables.clear();
        homeLeaves.clear();

        var builder = bento.dockBuilding();
        var root = builder.root("restored-root");

        // Document leaf (always exists, not persisted in the layout tree)
        var docLeaf = builder.leaf("document_tabs");
        docLeaf.setPruneWhenEmpty(false);

        var restoredContainer = LayoutTreeIO.restore(builder, state.dockRoot(), this::createDockable, homeLeaves::put);

        root.setOrientation(Orientation.VERTICAL);
        if (restoredContainer != null) {
            root.addContainers(docLeaf, restoredContainer);
        } else {
            root.addContainers(docLeaf);
        }

        rootBranch = root;
        centreShellBranch = root;
        documentLeaf = docLeaf;

        dockContainer.setCenter(rootBranch);
        dockContainer.setBottom(dockBar);

        // Defer divider positions — SplitPane ignores them before layout.
        deferDividers(root, 0.45);

        // Restore floating windows
        for (var fw : state.floatingWindows()) {
            var dockable = dockables.get(fw.paneId());
            if (dockable != null && mainStage != null && mainStage.getScene() != null) {
                bento.stageBuilding().newStageForDockable(
                    mainStage.getScene(), dockable, fw.x(), fw.y());
            }
        }
    }

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
                simStatus.setVisible(allSimSessions().stream().anyMatch(s -> s.simulating.get()));
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

    private void initializePanes() {
        for (var paneId : PaneId.values()) {
            if (paneId == PaneId.SEQUENCE_EDITOR
                    || paneId == PaneId.SIM_CONFIG_EDITOR
                    || paneId == PaneId.EIGENFIELD_EDITOR) continue;
            panes.put(paneId, createPane(paneId));
        }
    }

    private void initializeSidebars() {
        // Left sidebar: Explorer
        leftSidebar.addTool(new ToolSidebar.Tool("explorer",
            "Explorer", StudioIcons.create(StudioIconKind.PROJECT), panes.get(PaneId.EXPLORER)));
        leftSidebar.showTool("explorer"); // open by default

        // Right sidebar: Inspector, Messages, Points
        rightSidebar.addTool(new ToolSidebar.Tool("inspector",
            "Inspector", StudioIcons.create(StudioIconKind.SIMULATION), panes.get(PaneId.INSPECTOR)));
        rightSidebar.addTool(new ToolSidebar.Tool("messages",
            "Messages", StudioIcons.create(StudioIconKind.MESSAGES), panes.get(PaneId.MESSAGES)));
        rightSidebar.addTool(new ToolSidebar.Tool("points",
            "Points", StudioIcons.create(StudioIconKind.SIMULATION), panes.get(PaneId.POINTS)));
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
            case EXPLORER -> new ExplorerPane(ctx);
            case INSPECTOR -> new InspectorPane(ctx);
            case SPHERE -> new SphereWorkbenchPane(ctx);
            case CROSS_SECTION -> new GeometryPane(ctx);
            case POINTS -> new PointsWorkbenchPane(ctx);
            case TIMELINE -> new TimelineWorkbenchPane(ctx);
            case PHASE_MAP_Z -> new PhaseMapZPane(ctx);
            case PHASE_MAP_R -> new PhaseMapRPane(ctx);
            case TRACE_PHASE -> new PhaseTracePane(ctx);
            case TRACE_POLAR -> new PolarTracePane(ctx);
            case TRACE_MAGNITUDE -> new MagnitudeTracePane(ctx);
            case MESSAGES -> new MessagesPane(ctx);
            case SEQUENCE_EDITOR, SIM_CONFIG_EDITOR, EIGENFIELD_EDITOR ->
                throw new IllegalStateException("Editor panes are created per-document");
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

    private void installShellStatusBindings() {
        session.viewport.tC.addListener((obs, o, n) -> updateShellStatus());
        session.points.entries.addListener((javafx.collections.ListChangeListener<ax.xz.mri.ui.model.IsochromatEntry>) c ->
            updateShellStatus());
    }

    private void installWorkspaceSwitching() {
        session.project.setOnSequenceOpened(this::openSequenceTab);
        session.project.setOnSimConfigOpened(this::openSimConfigTab);
        session.project.setOnHardwareConfigOpened(this::openHardwareConfigTab);
        session.project.setOnEigenfieldOpened(this::openEigenfieldTab);
    }

    // --- BentoFX layout ---

    private void rebuildWorkbench() {
        bento = new Bento();
        configureBento();
        dockables.clear();
        homeLeaves.clear();

        var builder = bento.dockBuilding();
        var root = builder.root("studio-root");

        // Layout:
        //   Top: Document editor tabs (DAW, import views, config editors)
        //   Middle: Analysis tools = (Geometry + Sphere) | (PhaseMaps + Traces)
        //   Bottom: Timeline (collapsible bar)
        var centreShell = builder.branch("centre-shell");

        // Document editor leaf (top)
        var docLeaf = builder.leaf("document_tabs");
        docLeaf.setPruneWhenEmpty(false);

        // Analysis leaf (middle) — ALL analysis panes as tabs in one leaf.
        // Click a tab to view it, click the selected tab to collapse the entire leaf
        // to a thin tab bar. Users can drag panes out to float if they need side-by-side.
        var analysisLeaf = builder.leaf("analysis_tabs");
        analysisLeaf.setPruneWhenEmpty(false);
        for (var paneId : ANALYSIS_PANE_IDS) {
            if (paneId == PaneId.TIMELINE) continue; // timeline gets its own leaf
            var dockable = createDockable(builder, paneId);
            if (dockable == null) continue;
            homeLeaves.put(paneId, analysisLeaf);
            analysisLeaf.addDockable(dockable);
        }
        if (!analysisLeaf.getDockables().isEmpty()) {
            analysisLeaf.selectDockable(analysisLeaf.getDockables().getFirst());
        }

        // Timeline leaf (bottom) — its own leaf so it collapses independently
        var timeline = registerLeaf(builder, PaneId.TIMELINE);

        // Hierarchy: nest so BOTH analysis and timeline are at edges (BentoFX
        // collapse only works on first/last child of a branch).
        //   centreShell = docLeaf | bottomArea
        //   bottomArea  = analysisLeaf | timeline
        // analysisLeaf is FIRST in bottomArea → can collapse ✓
        // timeline is LAST in bottomArea → can collapse ✓
        var bottomArea = builder.branch("bottom-area");
        root.setOrientation(Orientation.HORIZONTAL);
        centreShell.setOrientation(Orientation.VERTICAL);
        bottomArea.setOrientation(Orientation.VERTICAL);
        root.addContainers(centreShell);
        centreShell.addContainers(docLeaf, bottomArea);
        bottomArea.addContainers(analysisLeaf, timeline);

        deferDividers(centreShell, 0.45);  // editors 45%, bottom area 55%
        deferDividers(bottomArea, 0.75);   // analysis 75%, timeline 25%

        rootBranch = root;
        centreShellBranch = centreShell;
        documentLeaf = docLeaf;

        dockContainer.setCenter(rootBranch);
        dockContainer.setBottom(dockBar); // auto-hides when empty
    }

    private DockContainerLeaf registerLeaf(software.coley.bentofx.building.DockBuilding builder, PaneId paneId) {
        var leaf = builder.leaf(paneId.name().toLowerCase());
        leaf.setPruneWhenEmpty(true);
        var dockable = createDockable(builder, paneId);
        if (dockable != null) {
            homeLeaves.put(paneId, leaf);
            leaf.addDockable(dockable);
            leaf.selectDockable(dockable);
        }
        return leaf;
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
        shellStatus.set(String.format("Tab: %s | Cursor: %.1f \u03bcs | Points: %d (%d visible)",
            tabName, session.viewport.tC.get(), session.points.entries.size(), visible));
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
