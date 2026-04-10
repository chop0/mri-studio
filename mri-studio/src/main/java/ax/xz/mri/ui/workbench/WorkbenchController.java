package ax.xz.mri.ui.workbench;

import ax.xz.mri.project.ActiveCapture;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.ui.viewmodel.SequenceSimulationSession;
import ax.xz.mri.ui.viewmodel.StudioSession;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import ax.xz.mri.ui.workbench.pane.ExplorerPane;
import ax.xz.mri.ui.workbench.pane.GeometryPane;
import ax.xz.mri.ui.workbench.pane.InspectorPane;
import ax.xz.mri.ui.workbench.pane.MagnitudeTracePane;
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
import javafx.stage.FileChooser;
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

    // BentoFX layout
    private Bento bento;
    private DockContainerRootBranch rootBranch;
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
        resetLayout();
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

        openTabs.add(tab);
        documentLeaf.addDockable(dockable);
        documentLeaf.selectDockable(dockable);
    }

    /** Open a sequence as a workspace tab. */
    public void openSequenceTab(SequenceDocument document) {
        openTab(document.id().value(), document.name(),
            new SequenceEditorProvider(document, session, this));
    }

    /** Open an imported capture as a workspace tab. */
    public void openImportTab(ProjectNodeId nodeId, ActiveCapture capture) {
        openTab(nodeId.value(), capture.name(),
            new ImportViewerProvider(capture, session, this));
    }

    /** Open a sim config as a workspace tab. */
    public void openSimConfigTab(SimulationConfigDocument configDoc) {
        openTab(configDoc.id().value(), configDoc.name(),
            new SimConfigEditorProvider(configDoc, session, this));
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

            // Push incoming data and restore state
            newTab.editor().activate(session);
            if (newTab.snapshot() != null) {
                newTab.editor().restoreState(session, newTab.snapshot());
            } else {
                // First activation — fit geometry to data bounds
                var data = session.document.blochData.get();
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
        } finally {
            switchingTabs = false;
        }
        updateShellStatus();
    }

    /** Close a workspace tab with dirty check. */
    public void closeTab(WorkspaceTab tab) {
        if (tab.editor().isDirty()) {
            var alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Unsaved Changes");
            alert.setHeaderText("Save changes to " + tab.rawName() + "?");
            alert.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO, ButtonType.CANCEL);
            var result = alert.showAndWait().orElse(ButtonType.CANCEL);
            if (result == ButtonType.YES) tab.editor().save();
            else if (result == ButtonType.CANCEL) return;
        }

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

    /** Get all open simulation sessions (for pushing config updates). */
    public java.util.Collection<SequenceSimulationSession> allSimSessions() {
        return openTabs.stream()
            .map(WorkspaceTab::editor)
            .filter(SequenceEditorProvider.class::isInstance)
            .map(e -> ((SequenceEditorProvider) e).simSession)
            .toList();
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
        var d = dockables.get(paneId); var hl = homeLeaves.get(paneId);
        if (d == null || hl == null) return;
        hl.addDockable(d); hl.selectDockable(d); focusPane(paneId);
    }

    public void resetLayout() { rebuildWorkbench(); }
    public void loadLayoutFromStore() { resetLayout(); }
    public void saveLayoutToStore() {}

    public void markTimelineStale(boolean stale) {
        // No-op for now — per-doc timelines handle their own state
    }

    // --- File operations ---

    public void importJsonChooser() {
        var chooser = new FileChooser();
        chooser.setTitle("Import Legacy Bloch JSON");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        File file = chooser.showOpenDialog(mainStage);
        if (file != null) loadFile(file);
    }

    public void openProjectChooser() {
        var chooser = new DirectoryChooser();
        chooser.setTitle("Open Project");
        File dir = chooser.showDialog(mainStage);
        if (dir == null) return;
        try { session.project.openProject(dir.toPath()); updateShellStatus(); }
        catch (Exception ex) { showError("Failed to open project", ex.getMessage()); }
    }

    public boolean confirmCloseAllEditors() {
        for (var tab : java.util.List.copyOf(openTabs)) {
            if (tab.editor().isDirty()) {
                var alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Unsaved Changes");
                alert.setHeaderText("Save changes to " + tab.rawName() + "?");
                alert.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO, ButtonType.CANCEL);
                var result = alert.showAndWait().orElse(ButtonType.CANCEL);
                if (result == ButtonType.YES) tab.editor().save();
                else if (result == ButtonType.CANCEL) return false;
            }
        }
        return true;
    }

    public void saveContextual() {
        var tab = activeTab.get();
        if (tab != null && tab.editor().isDirty()) { tab.editor().save(); return; }
        saveProject();
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

    public void loadFile(File file) {
        try { session.project.openImport(file); updateShellStatus(); }
        catch (Exception ex) { showError("Failed to load file", ex.getMessage()); }
    }

    public void reloadCurrentFile() {
        try { session.project.reloadSelectedImport(); updateShellStatus(); }
        catch (Exception ex) { showError("Failed to reload import", ex.getMessage()); }
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
                || paneId == PaneId.POINTS) continue;
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
            var repo = session.project.repository.get();
            if (tab != null) contextLabel.setText(tab.displayName());
            else contextLabel.setText(repo.manifest().name());
        };
        refresh.run();
        activeTab.addListener((obs, o, n) -> refresh.run());
        session.project.repository.addListener((obs, o, n) -> refresh.run());

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
            if (paneId == PaneId.SEQUENCE_EDITOR || paneId == PaneId.SIM_CONFIG_EDITOR) continue;
            panes.put(paneId, createPane(paneId));
        }
    }

    private void initializeSidebars() {
        // Left sidebar: Explorer
        leftSidebar.addTool(new ToolSidebar.Tool("explorer",
            "Explorer", StudioIcons.create(StudioIconKind.PROJECT), panes.get(PaneId.EXPLORER)));
        leftSidebar.showTool("explorer"); // open by default

        // Right sidebar: Inspector, Points
        rightSidebar.addTool(new ToolSidebar.Tool("inspector",
            "Inspector", StudioIcons.create(StudioIconKind.IMPORT), panes.get(PaneId.INSPECTOR)));
        rightSidebar.addTool(new ToolSidebar.Tool("points",
            "Points", StudioIcons.create(StudioIconKind.CAPTURE), panes.get(PaneId.POINTS)));
        rightSidebar.showTool("inspector"); // open by default
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
            case SEQUENCE_EDITOR, SIM_CONFIG_EDITOR ->
                throw new IllegalStateException("Editor panes are created per-document");
        };
    }

    private void registerCommands() {
        commandRegistry.register(new PaneAction(CommandId.OPEN_PROJECT, "Open Project\u2026", this::openProjectChooser));
        commandRegistry.register(new PaneAction(CommandId.IMPORT_JSON, "Import JSON\u2026", this::importJsonChooser));
        commandRegistry.register(new PaneAction(CommandId.RELOAD_FILE, "Reload", this::reloadCurrentFile));
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
        commandRegistry.register(new PaneAction(CommandId.PROMOTE_SNAPSHOT_TO_SEQUENCE, "Promote to Sequence",
            session.project::promoteActiveSnapshotToSequence));
        commandRegistry.register(new PaneAction(CommandId.DELETE_SEQUENCE, "Delete Sequence", () -> {
            var n = session.project.inspector.inspectedNodeId.get();
            if (n != null && session.project.repository.get().node(n) instanceof SequenceDocument)
                session.project.deleteSequence(n);
        }));
        commandRegistry.register(new PaneAction(CommandId.NEW_SIM_CONFIG, "New Sim Config", this::newSimConfigWizard));
        commandRegistry.register(new PaneAction(CommandId.NEW_EIGENFIELD, "New Eigenfield", this::newEigenfieldWizard));
        commandRegistry.register(new PaneAction(CommandId.NEW_SEQUENCE, "New Sequence", this::newSequenceWizard));
    }

    private void newSimConfigWizard() {
        ax.xz.mri.ui.wizard.NewSimConfigWizard.show(mainStage, session.project).ifPresent(doc -> {
            session.project.selectNode(doc.id());
            openSimConfigTab(doc);
        });
    }

    private void newEigenfieldWizard() {
        ax.xz.mri.ui.wizard.NewEigenfieldWizard.show(mainStage, session.project).ifPresent(ef ->
            session.project.selectNode(ef.id()));
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
        // Opening a sequence from the explorer → create tab
        session.project.setOnSequenceOpened(this::openSequenceTab);
        // Opening an import/capture → create tab
        session.project.setOnCaptureOpened(this::openImportTab);
        // Opening a sim config → create tab
        session.project.setOnSimConfigOpened(this::openSimConfigTab);
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

        // Document editor leaf (top) — BentoFX tab headers serve as document tabs
        var docLeaf = builder.leaf("document_tabs");
        docLeaf.setPruneWhenEmpty(false);

        // Analysis area (middle)
        var analysisArea = builder.branch("analysis-area");
        var analysisLeft = builder.branch("analysis-left");
        var analysisRight = builder.branch("analysis-right");
        var phaseMaps = builder.branch("phase-maps");
        var phaseTraces = builder.branch("phase-traces");

        var geometry = registerLeaf(builder, PaneId.CROSS_SECTION);
        var sphere = registerLeaf(builder, PaneId.SPHERE);
        var phaseMapZ = registerLeaf(builder, PaneId.PHASE_MAP_Z);
        var phaseMapR = registerLeaf(builder, PaneId.PHASE_MAP_R);
        var tracePhase = registerLeaf(builder, PaneId.TRACE_PHASE);
        var tracePolar = registerLeaf(builder, PaneId.TRACE_POLAR);
        var traceMagnitude = registerLeaf(builder, PaneId.TRACE_MAGNITUDE);

        // Timeline (bottom bar)
        var timeline = registerLeaf(builder, PaneId.TIMELINE);

        // Orientations
        root.setOrientation(Orientation.HORIZONTAL);
        centreShell.setOrientation(Orientation.VERTICAL);
        analysisArea.setOrientation(Orientation.HORIZONTAL);
        analysisLeft.setOrientation(Orientation.HORIZONTAL);
        analysisRight.setOrientation(Orientation.VERTICAL);
        phaseMaps.setOrientation(Orientation.HORIZONTAL);
        phaseTraces.setOrientation(Orientation.HORIZONTAL);

        // Hierarchy:
        //   centreShell = docLeaf | analysisArea | timeline
        //   analysisArea = analysisLeft(geometry, sphere) | analysisRight(phaseMaps, traces, magnitude)
        root.addContainers(centreShell);
        centreShell.addContainers(docLeaf, analysisArea, timeline);
        analysisArea.addContainers(analysisLeft, analysisRight);
        analysisLeft.addContainers(geometry, sphere);
        analysisRight.addContainers(phaseMaps, phaseTraces, traceMagnitude);
        phaseMaps.addContainers(phaseMapZ, phaseMapR);
        phaseTraces.addContainers(tracePhase, tracePolar);

        // Divider positions
        centreShell.setDividerPositions(0.40, 0.85); // editors 40%, analysis 45%, timeline 15%
        analysisArea.setDividerPositions(0.4);        // left (geo+sphere) 40%, right (maps+traces) 60%
        analysisLeft.setDividerPositions(0.55);       // geometry 55%, sphere 45% (square-ish)
        analysisRight.setDividerPositions(0.4, 0.7);
        phaseMaps.setDividerPositions(0.5);
        phaseTraces.setDividerPositions(0.5);

        rootBranch = root;
        documentLeaf = docLeaf;

        dockContainer.setCenter(rootBranch);
    }

    private DockContainerLeaf registerLeaf(software.coley.bentofx.building.DockBuilding builder, PaneId paneId) {
        var leaf = builder.leaf(paneId.name().toLowerCase());
        leaf.setPruneWhenEmpty(true);
        var dockable = builder.dockable(paneId.name());
        dockable.setTitle(paneId.title());
        dockable.setNode(panes.get(paneId));
        dockable.setClosable(false);
        dockable.setCanBeDragged(true);
        dockable.setCanBeDroppedToNewWindow(true);
        dockable.setDragGroup(STUDIO_DRAG_GROUP);
        dockable.setContextMenuFactory(ignored -> buildToolWindowMenu(paneId));
        dockables.put(paneId, dockable);
        homeLeaves.put(paneId, leaf);
        leaf.addDockable(dockable);
        leaf.selectDockable(dockable);
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
        if (tab.editor().isDirty()) {
            var saveItem = new MenuItem("Save");
            saveItem.setOnAction(e -> tab.editor().save());
            menu.getItems().add(0, saveItem);
            menu.getItems().add(1, new SeparatorMenuItem());
        }
        return menu;
    }

    /** Context menu for analysis tool window tabs. */
    private javafx.scene.control.ContextMenu buildToolWindowMenu(PaneId paneId) {
        var menu = new javafx.scene.control.ContextMenu();
        var floatItem = new MenuItem(isFloating(paneId) ? "Dock to Default" : "Float");
        floatItem.setOnAction(e -> {
            if (isFloating(paneId)) dockPane(paneId); else floatPane(paneId);
        });
        var dockItem = new MenuItem("Restore to Default Position");
        dockItem.setOnAction(e -> dockPane(paneId));
        menu.getItems().addAll(floatItem, dockItem);
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
    }

    private Scene createDockingScene(Scene src, javafx.scene.layout.Region region, double w, double h) {
        var scene = new Scene(region, w, h);
        if (src != null) scene.getStylesheets().setAll(src.getStylesheets());
        return scene;
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
