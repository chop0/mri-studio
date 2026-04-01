package ax.xz.mri.ui.workbench;

import ax.xz.mri.project.ProjectNode;
import ax.xz.mri.project.ProjectNodeId;
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
import ax.xz.mri.ui.viewmodel.StudioSession;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
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
import java.util.Optional;

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
    private Stage mainStage;
    private boolean disposed;

    public WorkbenchController(StudioSession session) {
        this.session = session;
        initializePanes();
        registerCommands();
        installShellStatusBindings();
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
            if (paneId == PaneId.EXPLORER || paneId == PaneId.INSPECTOR) continue;
            var focus = new MenuItem("Focus " + paneId.title());
            focus.setOnAction(event -> focusPane(paneId));
            menu.getItems().add(focus);
        }
    }

    public Node buildMainToolStrip() {
        var projectLabel = new Label();
        var objectLabel = new Label();
        Runnable refreshLabels = () -> {
            String projectName = session.project.repository.get().manifest().name();
            projectLabel.setText(session.project.projectRoot.get() == null
                ? "Project: " + projectName + " (unsaved)"
                : "Project: " + projectName);
            objectLabel.setText("Open: " + describeNode(session.project.workspace.activeNodeId.get()));
        };
        refreshLabels.run();
        session.project.repository.addListener((obs, oldValue, newValue) -> refreshLabels.run());
        session.project.workspace.activeNodeId.addListener((obs, oldValue, newValue) -> refreshLabels.run());
        session.project.explorer.structureRevision.addListener((obs, oldValue, newValue) -> refreshLabels.run());
        session.project.projectRoot.addListener((obs, oldValue, newValue) -> refreshLabels.run());

        var activePaneLabel = new Label();
        session.docking.activePaneId.addListener((obs, oldValue, newValue) ->
            activePaneLabel.setText(newValue == null ? "No active pane" : "Active: " + newValue.title()));
        activePaneLabel.setText("No active pane");

        var computeStatus = new Label();
        session.derived.computing.addListener((obs, oldValue, newValue) ->
            computeStatus.setText(newValue ? "Computing\u2026" : ""));
        computeStatus.setText("");

        var bar = new javafx.scene.layout.HBox(8,
            new Button("Open Project") {{
                setOnAction(event -> openProjectChooser());
            }},
            new Button("Import JSON") {{
                setOnAction(event -> importJsonChooser());
            }},
            projectLabel,
            objectLabel,
            new javafx.scene.layout.Region() {{
                javafx.scene.layout.HBox.setHgrow(this, javafx.scene.layout.Priority.ALWAYS);
            }},
            activePaneLabel,
            computeStatus
        );
        bar.getStyleClass().add("shell-tool-strip");
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        bar.setPadding(new javafx.geometry.Insets(4, 6, 4, 6));
        return bar;
    }

    private void initializePanes() {
        for (var paneId : PaneId.values()) {
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
    }

    private void installShellStatusBindings() {
        session.project.workspace.activeNodeId.addListener((obs, oldValue, newValue) -> updateShellStatus());
        session.project.activeCapture.activeCapture.addListener((obs, oldValue, newValue) -> updateShellStatus());
        session.project.runNavigation.activeCaptureIndex.addListener((obs, oldValue, newValue) -> updateShellStatus());
        session.project.explorer.structureRevision.addListener((obs, oldValue, newValue) -> updateShellStatus());
        session.viewport.tC.addListener((obs, oldValue, newValue) -> updateShellStatus());
        session.points.entries.addListener((javafx.collections.ListChangeListener<ax.xz.mri.ui.model.IsochromatEntry>) change ->
            updateShellStatus());
        session.docking.activePaneId.addListener((obs, oldValue, newValue) -> updateShellStatus());
    }

    private void rebuildWorkbench() {
        bento = new Bento();
        configureBento();
        dockables.clear();
        homeLeaves.clear();

        var builder = bento.dockBuilding();
        var root = builder.root("studio-root");
        var explorer = registerLeaf(builder, PaneId.EXPLORER);
        var inspector = registerLeaf(builder, PaneId.INSPECTOR);
        var centreShell = builder.branch("centre-shell");
        var workspace = builder.branch("workspace");
        var upper = builder.branch("upper");
        var left = builder.branch("left");
        var lower = builder.branch("lower");
        var phaseMaps = builder.branch("phase-maps");
        var phaseTraces = builder.branch("phase-traces");

        var timeline = registerLeaf(builder, PaneId.TIMELINE);
        var sphere = registerLeaf(builder, PaneId.SPHERE);
        var geometry = registerLeaf(builder, PaneId.CROSS_SECTION);
        var points = registerLeaf(builder, PaneId.POINTS);
        var phaseMapZ = registerLeaf(builder, PaneId.PHASE_MAP_Z);
        var phaseMapR = registerLeaf(builder, PaneId.PHASE_MAP_R);
        var tracePhase = registerLeaf(builder, PaneId.TRACE_PHASE);
        var tracePolar = registerLeaf(builder, PaneId.TRACE_POLAR);
        var traceMagnitude = registerLeaf(builder, PaneId.TRACE_MAGNITUDE);

        root.setOrientation(Orientation.HORIZONTAL);
        centreShell.setOrientation(Orientation.VERTICAL);
        workspace.setOrientation(Orientation.VERTICAL);
        upper.setOrientation(Orientation.HORIZONTAL);
        left.setOrientation(Orientation.HORIZONTAL);
        lower.setOrientation(Orientation.HORIZONTAL);
        phaseMaps.setOrientation(Orientation.HORIZONTAL);
        phaseTraces.setOrientation(Orientation.HORIZONTAL);

        root.addContainers(explorer, centreShell, inspector);
        centreShell.addContainers(timeline, workspace);
        workspace.addContainers(upper, lower);
        upper.addContainers(left, points);
        left.addContainers(sphere, geometry);
        lower.addContainers(phaseMaps, phaseTraces, traceMagnitude);
        phaseMaps.addContainers(phaseMapZ, phaseMapR);
        phaseTraces.addContainers(tracePhase, tracePolar);

        root.setDividerPositions(0.14, 0.84);
        centreShell.setDividerPositions(0.26);
        workspace.setDividerPositions(0.62);
        upper.setDividerPositions(0.58);
        left.setDividerPositions(0.52);
        lower.setDividerPositions(0.44, 0.76);
        phaseMaps.setDividerPositions(0.5);
        phaseTraces.setDividerPositions(0.5);

        root.setContainerResizable(explorer, false);
        root.setContainerSizePx(explorer, 280);
        root.setContainerResizable(inspector, false);
        root.setContainerSizePx(inspector, 340);
        centreShell.setContainerResizable(timeline, false);
        centreShell.setContainerSizePx(timeline, 250);

        rootBranch = root;
        dockContainer.setCenter(rootBranch);
        activatePane(PaneId.TIMELINE);
    }

    private DockContainerLeaf registerLeaf(software.coley.bentofx.building.DockBuilding builder, PaneId paneId) {
        var leaf = builder.leaf(paneId.name().toLowerCase());
        leaf.setPruneWhenEmpty(true);
        var dockable = builder.dockable(paneId.name());
        dockable.setTitle(paneId.title());
        dockable.setNode(panes.get(paneId));
        dockable.setClosable(false);
        boolean pinned = paneId == PaneId.EXPLORER || paneId == PaneId.INSPECTOR;
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
        String openObject = describeNode(session.project.workspace.activeNodeId.get());
        String capture = activeCapture == null ? "\u2014" : activeCapture.name();
        String iter = activeCapture == null || activeCapture.iterationKey() == null ? "\u2014" : activeCapture.iterationKey();
        String activePane = session.docking.activePaneId.get() == null ? "\u2014" : session.docking.activePaneId.get().title();
        String paneStatus = session.docking.activePaneId.get() == null
            ? ""
            : paneStatuses.getOrDefault(session.docking.activePaneId.get(), "");
        long visible = session.points.entries.stream().filter(ax.xz.mri.ui.model.IsochromatEntry::visible).count();
        shellStatus.set(String.format(
            "Project: %s | Open: %s | Capture: %s | Iter: %s | Cursor: %.1f \u03bcs | Points: %d (%d visible) | Active: %s%s",
            session.project.repository.get().manifest().name(),
            openObject,
            capture,
            iter,
            session.viewport.tC.get(),
            session.points.entries.size(),
            visible,
            activePane,
            paneStatus == null || paneStatus.isBlank() ? "" : " | " + paneStatus
        ));
    }

    private String describeNode(ProjectNodeId nodeId) {
        if (nodeId == null) return "\u2014";
        ProjectNode node = session.project.repository.get().node(nodeId);
        return node == null ? nodeId.value() : ProjectDisplayNames.label(node);
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
