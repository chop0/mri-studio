package ax.xz.mri.ui.workbench;

import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import ax.xz.mri.ui.workbench.pane.ExplorerPane;
import ax.xz.mri.ui.workbench.pane.SequenceEditorPane;
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
import ax.xz.mri.ui.viewmodel.StudioSession;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;

import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
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


/** Owns pane activation, BentoFX docking, file loading, and shell command wiring. */
public class WorkbenchController {
    private static final int STUDIO_DRAG_GROUP = 1;

    private final StudioSession session;
    private final CommandRegistry commandRegistry = new CommandRegistry();
    private final StringProperty shellStatus = new SimpleStringProperty("Ready");
    private final BorderPane dockContainer = new BorderPane();
    private final Map<PaneId, WorkbenchPane> panes = new EnumMap<>(PaneId.class);
    private final Map<PaneId, Dockable> dockables = new EnumMap<>(PaneId.class);
    private final Map<PaneId, DockContainerLeaf> homeLeaves = new EnumMap<>(PaneId.class);
    private final Map<PaneId, String> paneStatuses = new EnumMap<>(PaneId.class);

    private Bento bento;
    private DockContainerRootBranch rootBranch;
    /** The centre-shell branch that holds analysis panes (timeline + workspace). */
    private software.coley.bentofx.layout.container.DockContainerBranch centreShellBranch;
    /** The sequence editor leaf, holds one tab per open sequence. */
    private DockContainerLeaf sequenceEditorLeaf;
    private boolean inSequenceEditorMode;
    private Stage mainStage;
    private boolean disposed;

    /** Maps sequence node ID → (pane, dockable, simSession) for multi-tab editing. */
    private final java.util.Map<String, SequenceEditorPane> openEditorPanes = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, Dockable> openEditorDockables = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, ax.xz.mri.ui.viewmodel.SequenceSimulationSession> openSimSessions = new java.util.LinkedHashMap<>();



    public WorkbenchController(StudioSession session) {
        this.session = session;
        initializePanes();
        registerCommands();
        installShellStatusBindings();
        installWorkspaceSwitching();
    }

    public void initialize(Stage stage) {
        this.mainStage = stage;
        resetLayout();
    }

    public Node dockRoot() {
        return dockContainer;
    }

    public StringProperty shellStatusProperty() {
        return shellStatus;
    }

    public CommandRegistry commandRegistry() {
        return commandRegistry;
    }

    public StudioSession session() {
        return session;
    }

    public void activatePane(PaneId paneId) {
        session.docking.activate(paneId);
        var dockable = dockables.get(paneId);
        if (dockable != null) {
            dockable.inContainer(container -> container.selectDockable(dockable));
        }
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
        if (pane != null && pane.getScene() != null && pane.getScene().getWindow() instanceof Stage stage) {
            stage.toFront();
            stage.requestFocus();
        }
        if (pane != null) pane.requestFocus();
    }

    public void floatPane(PaneId paneId) {
        if (mainStage == null || mainStage.getScene() == null) return;
        var dockable = dockables.get(paneId);
        if (dockable == null) return;
        if (isFloating(paneId)) {
            focusPane(paneId);
            return;
        }
        bento.stageBuilding().newStageForDockable(
            mainStage.getScene(),
            dockable,
            mainStage.getX() + 90,
            mainStage.getY() + 90
        );
        focusPane(paneId);
    }

    public void dockPane(PaneId paneId) {
        var dockable = dockables.get(paneId);
        var homeLeaf = homeLeaves.get(paneId);
        if (dockable == null || homeLeaf == null) return;
        homeLeaf.addDockable(dockable);
        homeLeaf.selectDockable(dockable);
        focusPane(paneId);
    }

    public void resetLayout() {
        rebuildWorkbench();
    }

    public void loadLayoutFromStore() {
        resetLayout();
    }

    public void saveLayoutToStore() {
    }

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
        File directory = chooser.showDialog(mainStage);
        if (directory == null) return;
        try {
            session.project.openProject(directory.toPath());
            updateShellStatus();
        } catch (Exception ex) {
            showError("Failed to open project", ex.getMessage());
        }
    }

    /**
     * Check all open editors for unsaved changes, prompting the user for each.
     * Returns true if all editors are saved/discarded, false if user cancelled.
     */
    public boolean confirmCloseAllEditors() {
        for (var entry : java.util.List.copyOf(openEditorPanes.entrySet())) {
            var pane = entry.getValue();
            if (pane.isDirty()) {
                var alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
                alert.setTitle("Unsaved Changes");
                alert.setHeaderText("Save changes to " + pane.tabTitle().replace(" *", "") + "?");
                alert.setContentText("Your changes will be lost if you don't save them.");
                alert.getButtonTypes().setAll(
                    javafx.scene.control.ButtonType.YES,
                    javafx.scene.control.ButtonType.NO,
                    javafx.scene.control.ButtonType.CANCEL
                );
                var result = alert.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL);
                if (result == javafx.scene.control.ButtonType.YES) {
                    pane.savePublic();
                } else if (result == javafx.scene.control.ButtonType.CANCEL) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Context-aware save: saves the active sequence if editing, otherwise the project. */
    public void saveContextual() {
        if (inSequenceEditorMode) {
            // Find the currently visible editor tab and save it
            for (var entry : openEditorPanes.entrySet()) {
                var dockable = openEditorDockables.get(entry.getKey());
                if (dockable != null && sequenceEditorLeaf.getSelectedDockable() == dockable) {
                    entry.getValue().savePublic();
                    return;
                }
            }
        }
        saveProject();
    }

    public void saveProject() {
        try {
            var root = session.project.projectRoot.get();
            if (root == null) {
                saveProjectAsChooser();
            } else {
                session.project.saveProject(root);
                updateShellStatus();
            }
        } catch (Exception ex) {
            showError("Failed to save project", ex.getMessage());
        }
    }

    public void saveProjectAsChooser() {
        var chooser = new DirectoryChooser();
        chooser.setTitle("Save Project As");
        File directory = chooser.showDialog(mainStage);
        if (directory == null) return;
        try {
            session.project.saveProject(directory.toPath());
            updateShellStatus();
        } catch (Exception ex) {
            showError("Failed to save project", ex.getMessage());
        }
    }

    public void loadFile(File file) {
        try {
            session.project.openImport(file);
            updateShellStatus();
        } catch (Exception ex) {
            showError("Failed to load file", ex.getMessage());
        }
    }

    public void reloadCurrentFile() {
        try {
            session.project.reloadSelectedImport();
            updateShellStatus();
        } catch (Exception ex) {
            showError("Failed to reload import", ex.getMessage());
        }
    }

    public void dispose() {
        if (disposed) return;
        disposed = true;
        panes.values().forEach(WorkbenchPane::dispose);
    }

    public void populateWindowMenu(Menu menu) {
        menu.getItems().clear();
        menu.getItems().addAll(
            menuItem("Float Active Pane", CommandId.FLOAT_ACTIVE_PANE),
            menuItem("Dock Active Pane", CommandId.DOCK_ACTIVE_PANE),
            menuItem("Focus Active Pane", CommandId.FOCUS_ACTIVE_PANE),
            new javafx.scene.control.SeparatorMenuItem()
        );
        for (var paneId : PaneId.values()) {
            if (paneId == PaneId.EXPLORER || paneId == PaneId.INSPECTOR || paneId == PaneId.SEQUENCE_EDITOR) continue;
            var focus = new MenuItem("Focus " + paneId.title());
            focus.setOnAction(event -> focusPane(paneId));
            menu.getItems().add(focus);
        }
    }

    public Node buildMainToolStrip() {
        var contextLabel = new Label();
        var computeStatus = new Label();
        session.derived.computing.addListener((obs, oldValue, newValue) ->
            computeStatus.setText(newValue ? "Computing\u2026" : ""));

        Runnable refreshContext = () -> {
            var nodeId = session.project.workspace.activeNodeId.get();
            var repo = session.project.repository.get();
            var node = nodeId == null ? null : repo.node(nodeId);
            if (node instanceof ax.xz.mri.project.SequenceDocument seq) {
                contextLabel.setText("Editing: " + seq.name()
                    + " \u2014 " + seq.segments().size() + " segments, "
                    + seq.pulse().stream().mapToInt(p -> p.steps().size()).sum() + " steps");
            } else {
                var capture = session.project.activeCapture.activeCapture.get();
                String projectName = repo.manifest().name();
                if (capture != null) {
                    contextLabel.setText(projectName + " \u203a " + capture.name()
                        + (capture.iterationKey() != null ? " \u203a Iter " + capture.iterationKey() : ""));
                } else if (node != null) {
                    contextLabel.setText(projectName + " \u203a " + node.name());
                } else {
                    contextLabel.setText(projectName);
                }
            }
        };
        refreshContext.run();
        session.project.workspace.activeNodeId.addListener((obs, o, n) -> refreshContext.run());
        session.project.activeCapture.activeCapture.addListener((obs, o, n) -> refreshContext.run());
        session.project.repository.addListener((obs, o, n) -> refreshContext.run());
        session.project.explorer.structureRevision.addListener((obs, o, n) -> refreshContext.run());

        // Simulation loading indicator
        var simStatus = new javafx.scene.control.ProgressIndicator(-1);
        simStatus.setPrefSize(14, 14);
        simStatus.setMaxSize(14, 14);
        simStatus.setVisible(false);
        simStatus.setStyle("-fx-progress-color: #e06000;");
        // Watch for any active sim session's simulating flag
        session.activeEditSession.addListener((obs, o, n) -> {
            if (o instanceof ax.xz.mri.ui.viewmodel.SequenceEditSession) {
                // Can't easily unbind from the old sim session, but it's harmless
            }
        });
        // Poll the sim sessions for activity (simple approach)
        var simStatusTimer = new javafx.animation.AnimationTimer() {
            @Override public void handle(long now) {
                boolean anySimulating = openSimSessions.values().stream()
                    .anyMatch(s -> s.simulating.get());
                simStatus.setVisible(anySimulating);
            }
        };
        simStatusTimer.start();

        var spacer = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        var bar = new javafx.scene.layout.HBox(8, contextLabel, spacer, simStatus, computeStatus);
        bar.getStyleClass().add("shell-tool-strip");
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        bar.setPadding(new javafx.geometry.Insets(4, 6, 4, 6));
        return bar;
    }

    private void initializePanes() {
        for (var paneId : PaneId.values()) {
            if (paneId == PaneId.SEQUENCE_EDITOR) continue; // created dynamically per open sequence
            panes.put(paneId, createPane(paneId));
        }
    }

    private WorkbenchPane createPane(PaneId paneId) {
        var context = new PaneContext(session, this, paneId);
        return switch (paneId) {
            case EXPLORER -> new ExplorerPane(context);
            case INSPECTOR -> new InspectorPane(context);
            case SPHERE -> new SphereWorkbenchPane(context);
            case CROSS_SECTION -> new GeometryPane(context);
            case POINTS -> new PointsWorkbenchPane(context);
            case TIMELINE -> new TimelineWorkbenchPane(context);
            case PHASE_MAP_Z -> new PhaseMapZPane(context);
            case PHASE_MAP_R -> new PhaseMapRPane(context);
            case TRACE_PHASE -> new PhaseTracePane(context);
            case TRACE_POLAR -> new PolarTracePane(context);
            case TRACE_MAGNITUDE -> new MagnitudeTracePane(context);
            case SEQUENCE_EDITOR -> throw new IllegalStateException("Sequence editors are created dynamically");
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
            var active = session.docking.activePaneId.get();
            if (active != null) floatPane(active);
        }));
        commandRegistry.register(new PaneAction(CommandId.DOCK_ACTIVE_PANE, "Dock Active Pane", () -> {
            var active = session.docking.activePaneId.get();
            if (active != null) dockPane(active);
        }));
        commandRegistry.register(new PaneAction(CommandId.FOCUS_ACTIVE_PANE, "Focus Active Pane", () -> {
            var active = session.docking.activePaneId.get();
            if (active != null) focusPane(active);
        }));
        commandRegistry.register(new PaneAction(CommandId.RESET_POINTS, "Reset Points", session.points::resetToDefaults));
        commandRegistry.register(new PaneAction(CommandId.CLEAR_USER_POINTS, "Clear User Points", session.points::clearUserPoints));
        commandRegistry.register(new PaneAction(CommandId.PROMOTE_SNAPSHOT_TO_SEQUENCE, "Promote to Sequence",
            session.project::promoteActiveSnapshotToSequence));
        commandRegistry.register(new PaneAction(CommandId.DELETE_SEQUENCE, "Delete Sequence", () -> {
            var nodeId = session.project.inspector.inspectedNodeId.get();
            if (nodeId != null && session.project.repository.get().node(nodeId) instanceof ax.xz.mri.project.SequenceDocument) {
                session.project.deleteSequence(nodeId);
            }
        }));
        commandRegistry.register(new PaneAction(CommandId.NEW_SIM_CONFIG, "New Simulation Config", this::newSimConfigWizard));
    }

    /**
     * File→New Simulation Config wizard.
     * Collects BlochData from any imported scenario/capture and extracts physics parameters.
     */
    private void newSimConfigWizard() {
        var repo = session.project.repository.get();

        // Build a list of all importable sources: (displayName, BlochData)
        // Walk all imports, find their captures, and collect BlochData
        record Source(String label, ax.xz.mri.model.scenario.BlochData data) {}
        var sources = new java.util.ArrayList<Source>();

        for (var importId : repo.importLinkIds()) {
            var link = (ax.xz.mri.project.ImportLinkDocument) repo.node(importId);
            if (link == null) continue;
            // Walk children recursively to find captures with BlochData
            java.util.Deque<ax.xz.mri.project.ProjectNodeId> stack = new java.util.ArrayDeque<>();
            stack.push(importId);
            while (!stack.isEmpty()) {
                var id = stack.pop();
                var node = repo.node(id);
                if (node == null) continue;
                var resolved = repo.resolveCapture(id);
                if (resolved != null && resolved.blochData() != null) {
                    sources.add(new Source(link.name() + " → " + resolved.name(), resolved.blochData()));
                }
                for (var childId : repo.childrenOf(id)) stack.push(childId);
            }
        }

        if (sources.isEmpty()) {
            showError("No data sources",
                "Import a JSON file first. The wizard extracts B₀, T₁, T₂ and spatial parameters from imported data.");
            return;
        }

        // Source selection dialog
        var sourceNames = sources.stream().map(Source::label).toList();
        var sourceDialog = new javafx.scene.control.ChoiceDialog<>(sourceNames.getFirst(), sourceNames);
        sourceDialog.setTitle("New Simulation Config");
        sourceDialog.setHeaderText("Extract parameters from imported data");
        sourceDialog.setContentText("Source capture:");
        sourceDialog.getDialogPane().setPrefWidth(450);

        sourceDialog.showAndWait().ifPresent(choice -> {
            var source = sources.stream().filter(s -> s.label.equals(choice)).findFirst().orElse(null);
            if (source == null) return;
            var field = source.data.field();

            var config = new ax.xz.mri.model.simulation.SimulationConfig(
                field.b0n,
                field.t1 * 1e3,  // seconds → ms (config stores ms)
                field.t2 * 1e3,  // seconds → ms
                field.gamma != 0 ? field.gamma : 267.522e6,
                ax.xz.mri.model.simulation.FieldPreset.UNIFORM,
                (field.sliceHalf != null ? field.sliceHalf : 0.005) * 1e3,
                field.fovZ * 1e3,
                field.fovX * 1e3,
                field.zMm != null ? field.zMm.length : 50,
                field.rMm != null ? field.rMm.length : 1,
                0.0,
                source.data.iso() != null
                    ? source.data.iso().stream()
                        .map(iso -> new ax.xz.mri.model.simulation.SimulationConfig.IsoPoint(
                            0, 0, iso.name(), iso.colour()))
                        .toList()
                    : java.util.List.of()
            );

            // Name dialog
            var nameDialog = new javafx.scene.control.TextInputDialog("Config from " + choice);
            nameDialog.setTitle("Simulation Config Name");
            nameDialog.setHeaderText("Name:");
            nameDialog.showAndWait().ifPresent(name -> {
                var doc = new ax.xz.mri.project.SimulationConfigDocument(
                    new ax.xz.mri.project.ProjectNodeId("simcfg-" + java.util.UUID.randomUUID()),
                    name.isBlank() ? "Imported Config" : name,
                    null,
                    config
                );
                repo.addSimConfig(doc);
                session.project.explorer.refresh();
                session.project.saveProjectQuietly();
                session.project.selectNode(doc.id());
            });
        });
    }

    private void installShellStatusBindings() {
        session.project.activeCapture.activeCapture.addListener((obs, oldValue, newValue) -> updateShellStatus());
        session.project.runNavigation.activeCaptureIndex.addListener((obs, oldValue, newValue) -> updateShellStatus());
        session.viewport.tC.addListener((obs, oldValue, newValue) -> updateShellStatus());
        session.points.entries.addListener((javafx.collections.ListChangeListener<ax.xz.mri.ui.model.IsochromatEntry>) change ->
            updateShellStatus());
    }

    private void rebuildWorkbench() {
        bento = new Bento();
        configureBento();
        dockables.clear();
        homeLeaves.clear();

        var builder = bento.dockBuilding();
        var root = builder.root("studio-root");

        // Left: Explorer
        var explorer = registerLeaf(builder, PaneId.EXPLORER);

        // Right panel: Bloch sphere on top, inspector on bottom
        var rightPanel = builder.branch("right-panel");
        var sphere = registerLeaf(builder, PaneId.SPHERE);
        var inspector = registerLeaf(builder, PaneId.INSPECTOR);

        // Centre: analysis panes on top, timeline/editor tabbed at bottom
        var centreShell = builder.branch("centre-shell");
        var workspace = builder.branch("workspace");
        var upper = builder.branch("upper");
        var lower = builder.branch("lower");
        var phaseMaps = builder.branch("phase-maps");
        var phaseTraces = builder.branch("phase-traces");

        // Timeline goes in the bottom leaf alongside editor tabs (tabbed)
        var timeline = registerLeaf(builder, PaneId.TIMELINE);
        var geometry = registerLeaf(builder, PaneId.CROSS_SECTION);
        var points = registerLeaf(builder, PaneId.POINTS);
        var phaseMapZ = registerLeaf(builder, PaneId.PHASE_MAP_Z);
        var phaseMapR = registerLeaf(builder, PaneId.PHASE_MAP_R);
        var tracePhase = registerLeaf(builder, PaneId.TRACE_PHASE);
        var tracePolar = registerLeaf(builder, PaneId.TRACE_POLAR);
        var traceMagnitude = registerLeaf(builder, PaneId.TRACE_MAGNITUDE);

        // Bottom leaf: holds timeline tab + sequence editor tabs (tabbed together)
        var bottomLeaf = builder.leaf("bottom_tabbed");
        bottomLeaf.setPruneWhenEmpty(false);
        // Move the timeline dockable from its own leaf into the bottom leaf
        var timelineDockable = dockables.get(PaneId.TIMELINE);
        timeline.removeDockable(timelineDockable);
        bottomLeaf.addDockable(timelineDockable);
        bottomLeaf.selectDockable(timelineDockable);

        // Orientations
        root.setOrientation(Orientation.HORIZONTAL);
        rightPanel.setOrientation(Orientation.VERTICAL);
        centreShell.setOrientation(Orientation.VERTICAL);
        workspace.setOrientation(Orientation.VERTICAL);
        upper.setOrientation(Orientation.HORIZONTAL);
        lower.setOrientation(Orientation.HORIZONTAL);
        phaseMaps.setOrientation(Orientation.HORIZONTAL);
        phaseTraces.setOrientation(Orientation.HORIZONTAL);

        // Unified layout:
        // Explorer | centreShell | rightPanel(sphere + inspector)
        root.addContainers(explorer, centreShell, rightPanel);
        rightPanel.addContainers(sphere, inspector);
        centreShell.addContainers(workspace, bottomLeaf);
        workspace.addContainers(upper, lower);
        upper.addContainers(geometry, points);
        lower.addContainers(phaseMaps, phaseTraces, traceMagnitude);
        phaseMaps.addContainers(phaseMapZ, phaseMapR);
        phaseTraces.addContainers(tracePhase, tracePolar);

        // Divider positions
        root.setDividerPositions(0.13, 0.78);
        rightPanel.setDividerPositions(0.55);
        centreShell.setDividerPositions(0.6); // workspace 60%, bottom 40%
        workspace.setDividerPositions(0.55);
        upper.setDividerPositions(0.55);
        lower.setDividerPositions(0.45, 0.75);
        phaseMaps.setDividerPositions(0.5);
        phaseTraces.setDividerPositions(0.5);

        // Fixed sizes
        root.setContainerResizable(explorer, false);
        root.setContainerSizePx(explorer, 260);

        rootBranch = root;
        centreShellBranch = centreShell;
        sequenceEditorLeaf = bottomLeaf;
        inSequenceEditorMode = false;

        dockContainer.setCenter(rootBranch);
        activatePane(PaneId.TIMELINE);
    }

    private void installWorkspaceSwitching() {
        session.project.workspace.activeNodeId.addListener((obs, oldNodeId, newNodeId) -> {
            if (newNodeId != null && session.project.repository.get().node(newNodeId)
                    instanceof ax.xz.mri.project.SequenceDocument seq) {
                showSequenceEditor(seq);
            } else {
                showAnalysisWorkspace();
            }
        });
    }

    private void showSequenceEditor(ax.xz.mri.project.SequenceDocument document) {
        String seqId = document.id().value();

        // Close any existing editor (only one sequence at a time)
        closeAllEditors();

        // Create editor pane + simulation session
        var editorPane = new SequenceEditorPane(new PaneContext(session, this, PaneId.SEQUENCE_EDITOR));
        openEditorPanes.put(seqId, editorPane);

        var simSession = new ax.xz.mri.ui.viewmodel.SequenceSimulationSession(editorPane.editSession(), session);
        openSimSessions.put(seqId, simSession);

        var dockable = bento.dockBuilding().dockable("seq-" + seqId + "-" + System.nanoTime());
        dockable.setTitle(document.name());
        dockable.setNode(editorPane);
        dockable.setClosable(true);
        dockable.setCanBeDragged(true);
        dockable.setCanBeDroppedToNewWindow(false);
        dockable.setDragGroup(STUDIO_DRAG_GROUP);
        openEditorDockables.put(seqId, dockable);

        editorPane.setOnTitleChanged(() -> dockable.setTitle(editorPane.tabTitle()));
        editorPane.open(document);
        editorPane.wireSimSession(simSession);
        sequenceEditorLeaf.addDockable(dockable);

        // Select the editor tab
        sequenceEditorLeaf.selectDockable(openEditorDockables.get(seqId));
        inSequenceEditorMode = true;

        // Update timeline title to "Simulated Timeline"
        updateTimelineTitle(true);

        // Trigger initial simulation
        simSession.simulate();
    }

    /** Close all open editor tabs (unsaved changes prompt for each). */
    private void closeAllEditors() {
        for (var entry : java.util.List.copyOf(openEditorDockables.entrySet())) {
            var pane = openEditorPanes.get(entry.getKey());
            if (pane != null && pane.isDirty()) {
                var alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
                alert.setTitle("Unsaved Changes");
                alert.setHeaderText("Save changes to " + pane.tabTitle().replace(" *", "") + "?");
                alert.getButtonTypes().setAll(
                    javafx.scene.control.ButtonType.YES,
                    javafx.scene.control.ButtonType.NO
                );
                if (alert.showAndWait().orElse(javafx.scene.control.ButtonType.NO) == javafx.scene.control.ButtonType.YES) {
                    pane.savePublic();
                }
            }
            sequenceEditorLeaf.removeDockable(entry.getValue());
            if (pane != null) pane.dispose();
            var simSess = openSimSessions.get(entry.getKey());
            if (simSess != null) simSess.dispose();
        }
        openEditorPanes.clear();
        openEditorDockables.clear();
        openSimSessions.clear();
    }

    private void showAnalysisWorkspace() {
        if (inSequenceEditorMode) {
            inSequenceEditorMode = false;
            session.activeEditSession.set(null);
            updateTimelineTitle(false);
            // Switch to timeline tab in the bottom leaf
            var timelineDockable = dockables.get(PaneId.TIMELINE);
            if (timelineDockable != null) sequenceEditorLeaf.selectDockable(timelineDockable);
        }
    }

    /** Get the simulation session for a given sequence ID. */
    public ax.xz.mri.ui.viewmodel.SequenceSimulationSession getSimSessionForSequence(String seqId) {
        return openSimSessions.get(seqId);
    }

    /** Update the timeline tab title depending on mode. */
    private void updateTimelineTitle(boolean simMode) {
        var timelineDockable = dockables.get(PaneId.TIMELINE);
        if (timelineDockable == null) return;
        if (simMode) {
            timelineDockable.setTitle("Simulated Timeline");
        } else {
            timelineDockable.setTitle("Timeline");
        }
    }

    /** Update the simulated timeline title with stale indicator. */
    public void markTimelineStale(boolean stale) {
        if (!inSequenceEditorMode) return;
        var timelineDockable = dockables.get(PaneId.TIMELINE);
        if (timelineDockable == null) return;
        timelineDockable.setTitle(stale ? "Simulated Timeline (out of date)" : "Simulated Timeline");
    }

    private DockContainerLeaf registerLeaf(software.coley.bentofx.building.DockBuilding builder, PaneId paneId) {
        var leaf = builder.leaf(paneId.name().toLowerCase());
        leaf.setPruneWhenEmpty(true);
        var dockable = builder.dockable(paneId.name());
        dockable.setTitle(paneId.title());
        dockable.setNode(panes.get(paneId));
        dockable.setClosable(false);
        boolean pinned = paneId == PaneId.EXPLORER || paneId == PaneId.INSPECTOR || paneId == PaneId.SEQUENCE_EDITOR;
        dockable.setCanBeDragged(!pinned);
        dockable.setCanBeDroppedToNewWindow(!pinned);
        dockable.setDragGroup(STUDIO_DRAG_GROUP);
        dockable.setContextMenuFactory(ignored -> pinned ? null : buildDockableMenu(paneId));
        dockables.put(paneId, dockable);
        homeLeaves.put(paneId, leaf);
        leaf.addDockable(dockable);
        leaf.selectDockable(dockable);
        return leaf;
    }

    private void configureBento() {
        bento.stageBuilding().setSceneFactory(this::createDockingScene);
        bento.events().addDockableSelectListener((path, dockable) -> {
            var paneId = paneIdOf(dockable);
            if (paneId != null) {
                session.docking.activate(paneId);
                updateShellStatus();
            }
        });
        bento.events().addDockableMoveListener((oldPath, newPath, dockable) -> {
            var paneId = paneIdOf(dockable);
            if (paneId != null) {
                session.docking.activate(paneId);
                updateShellStatus();
            }
        });
        bento.events().addDockableOpenListener((path, dockable) -> {
            var paneId = paneIdOf(dockable);
            if (paneId != null) {
                session.docking.activate(paneId);
                updateShellStatus();
            }
        });
        // Clean up closed sequence editor tabs (with unsaved-change prompt)
        bento.events().addDockableCloseListener((closePath, closedDockable) -> {
            String closedSeqId = null;
            for (var entry : openEditorDockables.entrySet()) {
                if (entry.getValue() == closedDockable) {
                    closedSeqId = entry.getKey();
                    break;
                }
            }
            if (closedSeqId != null) {
                var pane = openEditorPanes.get(closedSeqId);
                if (pane != null && pane.isDirty()) {
                    var alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
                    alert.setTitle("Unsaved Changes");
                    alert.setHeaderText("Save changes to " + pane.tabTitle().replace(" *", "") + "?");
                    alert.setContentText("Your changes will be lost if you don't save them.");
                    alert.getButtonTypes().setAll(
                        javafx.scene.control.ButtonType.YES,
                        javafx.scene.control.ButtonType.NO,
                        javafx.scene.control.ButtonType.CANCEL
                    );
                    var result = alert.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL);
                    if (result == javafx.scene.control.ButtonType.YES) {
                        pane.savePublic();
                    } else if (result == javafx.scene.control.ButtonType.CANCEL) {
                        // Re-add the dockable — user cancelled the close
                        sequenceEditorLeaf.addDockable(closedDockable);
                        sequenceEditorLeaf.selectDockable(closedDockable);
                        return;
                    }
                }
                openEditorPanes.remove(closedSeqId);
                openEditorDockables.remove(closedSeqId);
                var simSess = openSimSessions.remove(closedSeqId);
                if (pane != null) pane.dispose();
                if (simSess != null) simSess.dispose();
                if (openEditorDockables.isEmpty() && inSequenceEditorMode) {
                    showAnalysisWorkspace();
                }
            }
        });
    }

    private Scene createDockingScene(Scene sourceScene, javafx.scene.layout.Region region, double width, double height) {
        var scene = new Scene(region, width, height);
        if (sourceScene != null) {
            scene.getStylesheets().setAll(sourceScene.getStylesheets());
        }
        return scene;
    }

    private javafx.scene.control.ContextMenu buildDockableMenu(PaneId paneId) {
        var menu = new javafx.scene.control.ContextMenu();
        var focus = new MenuItem("Focus Pane");
        focus.setOnAction(event -> focusPane(paneId));
        var floatItem = new MenuItem(isFloating(paneId) ? "Raise Window" : "Float Pane");
        floatItem.setOnAction(event -> floatPane(paneId));
        var dockItem = new MenuItem("Dock To Default");
        dockItem.setOnAction(event -> dockPane(paneId));
        menu.getItems().addAll(focus, floatItem, dockItem);
        return menu;
    }

    private PaneId paneIdOf(Dockable dockable) {
        try {
            return dockable == null ? null : PaneId.valueOf(dockable.getIdentifier());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void updateShellStatus() {
        var activeCapture = session.project.activeCapture.activeCapture.get();
        String capture = activeCapture == null ? "\u2014" : activeCapture.name();
        String iter = activeCapture == null || activeCapture.iterationKey() == null ? "\u2014" : activeCapture.iterationKey();
        String paneStatus = session.docking.activePaneId.get() == null
            ? ""
            : paneStatuses.getOrDefault(session.docking.activePaneId.get(), "");
        long visible = session.points.entries.stream().filter(ax.xz.mri.ui.model.IsochromatEntry::visible).count();
        shellStatus.set(String.format(
            "Capture: %s | Iter: %s | Cursor: %.1f \u03bcs | Points: %d (%d visible)%s",
            capture,
            iter,
            session.viewport.tC.get(),
            session.points.entries.size(),
            visible,
            paneStatus == null || paneStatus.isBlank() ? "" : " | " + paneStatus
        ));
    }


    private void showError(String title, String message) {
        new Alert(Alert.AlertType.ERROR, message).showAndWait();
    }

    private MenuItem menuItem(String label, CommandId id) {
        var item = new MenuItem(label);
        item.setOnAction(event -> commandRegistry.execute(id));
        return item;
    }
}
