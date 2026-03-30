package ax.xz.mri.ui.workbench;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.service.io.BlochDataReader;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import ax.xz.mri.ui.workbench.layout.DockNode;
import ax.xz.mri.ui.workbench.layout.FloatingWindowState;
import ax.xz.mri.ui.workbench.layout.PaneLeaf;
import ax.xz.mri.ui.workbench.layout.SplitNode;
import ax.xz.mri.ui.workbench.layout.TabNode;
import ax.xz.mri.ui.workbench.layout.WorkbenchLayoutState;
import ax.xz.mri.ui.workbench.pane.GeometryPane;
import ax.xz.mri.ui.workbench.pane.MagnitudeTracePane;
import ax.xz.mri.ui.workbench.pane.PhaseMapRPane;
import ax.xz.mri.ui.workbench.pane.PhaseMapZPane;
import ax.xz.mri.ui.workbench.pane.PhaseTracePane;
import ax.xz.mri.ui.workbench.pane.PointsWorkbenchPane;
import ax.xz.mri.ui.workbench.pane.PolarTracePane;
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
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Owns pane activation, dock/floating state, file loading, and layout persistence. */
public class WorkbenchController {
    private final StudioSession session;
    private final PersistentLayoutStore layoutStore;
    private final CommandRegistry commandRegistry = new CommandRegistry();
    private final StringProperty shellStatus = new SimpleStringProperty("Ready");
    private final BorderPane dockContainer = new BorderPane();
    private final Map<PaneId, WorkbenchPane> panes = new EnumMap<>(PaneId.class);
    private final Map<PaneId, StackPane> dockSlots = new EnumMap<>(PaneId.class);
    private final Map<PaneId, Stage> floatingStages = new EnumMap<>(PaneId.class);
    private final Map<PaneId, String> paneStatuses = new EnumMap<>(PaneId.class);

    private Stage mainStage;
    private WorkbenchLayoutState currentLayout;
    private boolean disposed;

    public WorkbenchController(StudioSession session) {
        this.session = session;
        this.layoutStore = new PersistentLayoutStore(Path.of(System.getProperty("user.home"), ".mri-studio", "layout.json"));
        initializePanes();
        registerCommands();
        installShellStatusBindings();
    }

    public void initialize(Stage stage) {
        this.mainStage = stage;
        loadLayoutFromStore();
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
        updateShellStatus();
    }

    public void setPaneStatus(PaneId paneId, String text) {
        paneStatuses.put(paneId, text == null ? "" : text);
        updateShellStatus();
    }

    public boolean isFloating(PaneId paneId) {
        return session.docking.floatingPanes.contains(paneId);
    }

    public void focusPane(PaneId paneId) {
        if (floatingStages.containsKey(paneId)) {
            floatingStages.get(paneId).toFront();
            floatingStages.get(paneId).requestFocus();
        } else if (panes.containsKey(paneId)) {
            panes.get(paneId).requestFocus();
        }
        activatePane(paneId);
    }

    public void floatPane(PaneId paneId) {
        if (isFloating(paneId)) return;
        var pane = panes.get(paneId);
        var slot = dockSlots.get(paneId);
        if (pane == null || slot == null || mainStage == null) return;

        slot.getChildren().setAll(buildFloatedPlaceholder(paneId));
        session.docking.floatingPanes.add(paneId);

        var stage = new Stage();
        stage.initOwner(mainStage);
        stage.setTitle(paneId.title() + " - MRI Studio");
        stage.setScene(new Scene(pane, 500, 360));
        stage.setOnCloseRequest(event -> {
            event.consume();
            dockPane(paneId);
        });
        stage.show();
        floatingStages.put(paneId, stage);
        activatePane(paneId);
        updateShellStatus();
    }

    public void dockPane(PaneId paneId) {
        var pane = panes.get(paneId);
        var slot = dockSlots.get(paneId);
        if (pane == null || slot == null) return;

        var stage = floatingStages.remove(paneId);
        if (stage != null) {
            stage.setOnCloseRequest(null);
            stage.hide();
        }

        session.docking.floatingPanes.remove(paneId);
        slot.getChildren().setAll(pane);
        activatePane(paneId);
        updateShellStatus();
    }

    public void resetLayout() {
        applyLayout(defaultLayout());
    }

    public void loadLayoutFromStore() {
        applyLayout(layoutStore.load().orElseGet(this::defaultLayout));
    }

    public void saveLayoutToStore() {
        try {
            layoutStore.save(snapshotLayout());
        } catch (IOException ex) {
            showError("Failed to save layout", ex.getMessage());
        }
    }

    public void openFileChooser() {
        var chooser = new FileChooser();
        chooser.setTitle("Open bloch_data.json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        File file = chooser.showOpenDialog(mainStage);
        if (file != null) {
            loadFile(file);
        }
    }

    public void loadFile(File file) {
        try {
            BlochData data = BlochDataReader.read(file);
            session.setDocument(file, data);
            updateShellStatus();
        } catch (Exception ex) {
            showError("Failed to load file", ex.getMessage());
        }
    }

    public void reloadCurrentFile() {
        Optional.ofNullable(session.document.currentFile.get()).ifPresent(this::loadFile);
    }

    public void dispose() {
        if (disposed) return;
        disposed = true;

        for (var stage : new ArrayList<>(floatingStages.values())) {
            stage.setOnCloseRequest(null);
            stage.hide();
        }
        floatingStages.clear();
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
            var focus = new MenuItem("Focus " + paneId.title());
            focus.setOnAction(event -> focusPane(paneId));
            menu.getItems().add(focus);
        }
    }

    public Node buildMainToolStrip() {
        var scenarioBox = new javafx.scene.control.ComboBox<String>();
        scenarioBox.setPromptText("Scenario");
        scenarioBox.setPrefWidth(180);
        scenarioBox.setItems(session.document.scenarioKeys);
        scenarioBox.valueProperty().bindBidirectional(session.document.currentScenario);

        var iterSlider = new javafx.scene.control.Slider(0, 1, 0);
        iterSlider.setPrefWidth(180);
        iterSlider.setMinWidth(160);
        iterSlider.setShowTickMarks(true);
        iterSlider.setShowTickLabels(false);
        iterSlider.setSnapToTicks(true);
        iterSlider.setMajorTickUnit(1);
        iterSlider.setMinorTickCount(0);
        iterSlider.setBlockIncrement(1);
        iterSlider.getStyleClass().add("shell-iteration-slider");
        var iterLabel = new Label("\u2014");
        var iterCountLabel = new Label("0/0");
        iterCountLabel.getStyleClass().add("shell-iteration-count");
        session.document.iterationKeys.addListener((javafx.collections.ListChangeListener<String>) change -> {
            int size = session.document.iterationKeys.size();
            iterSlider.setMin(0);
            iterSlider.setMax(Math.max(0, size - 1));
            iterSlider.setDisable(size == 0);
            iterSlider.setOpacity(size == 0 ? 0.35 : 1.0);
            iterLabel.setText(size > 0 ? session.document.iterationKeys.get(session.document.iterationIndex.get()) : "\u2014");
            int index = size == 0 ? 0 : Math.max(0, Math.min(session.document.iterationIndex.get(), size - 1));
            iterCountLabel.setText(size == 0 ? "0/0" : (index + 1) + "/" + size);
        });
        session.document.iterationIndex.addListener((obs, oldValue, newValue) -> {
            iterSlider.setValue(newValue.doubleValue());
            if (!session.document.iterationKeys.isEmpty()) {
                int index = Math.max(0, Math.min(newValue.intValue(), session.document.iterationKeys.size() - 1));
                iterLabel.setText(session.document.iterationKeys.get(index));
                iterCountLabel.setText((index + 1) + "/" + session.document.iterationKeys.size());
            } else {
                iterLabel.setText("\u2014");
                iterCountLabel.setText("0/0");
            }
        });
        iterSlider.valueProperty().addListener((obs, oldValue, newValue) ->
            session.document.iterationIndex.set((int) Math.round(newValue.doubleValue())));
        iterSlider.setDisable(true);
        iterSlider.setOpacity(0.35);

        var activePaneLabel = new Label();
        session.docking.activePaneId.addListener((obs, oldValue, newValue) ->
            activePaneLabel.setText(newValue == null ? "No active pane" : "Active: " + newValue.title()));
        activePaneLabel.setText("No active pane");

        var computeStatus = new Label();
        session.derived.computing.addListener((obs, oldValue, newValue) ->
            computeStatus.setText(newValue ? "Computing\u2026" : ""));
        computeStatus.setText("");

        var bar = new javafx.scene.layout.HBox(8,
            new Button("Open") {{
                setOnAction(event -> openFileChooser());
            }},
            new Label("Scenario:"), scenarioBox,
            new Label("Iter:"), iterSlider, iterLabel, iterCountLabel,
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
            case SPHERE -> new SphereWorkbenchPane(context);
            case GEOMETRY -> new GeometryPane(context);
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
        commandRegistry.register(new PaneAction(CommandId.OPEN_FILE, "Open\u2026", this::openFileChooser));
        commandRegistry.register(new PaneAction(CommandId.RELOAD_FILE, "Reload", this::reloadCurrentFile));
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
    }

    private void installShellStatusBindings() {
        session.document.currentScenario.addListener((obs, oldValue, newValue) -> updateShellStatus());
        session.document.iterationIndex.addListener((obs, oldValue, newValue) -> updateShellStatus());
        session.viewport.tC.addListener((obs, oldValue, newValue) -> updateShellStatus());
        session.points.entries.addListener((javafx.collections.ListChangeListener<ax.xz.mri.ui.model.IsochromatEntry>) change ->
            updateShellStatus());
        session.docking.activePaneId.addListener((obs, oldValue, newValue) -> updateShellStatus());
    }

    private void updateShellStatus() {
        String scenario = session.document.currentScenario.get() == null ? "\u2014" : session.document.currentScenario.get();
        String iter = session.document.iterationKeys.isEmpty()
            ? "\u2014"
            : session.document.iterationKeys.get(Math.max(0,
                Math.min(session.document.iterationIndex.get(), session.document.iterationKeys.size() - 1)));
        String activePane = session.docking.activePaneId.get() == null ? "\u2014" : session.docking.activePaneId.get().title();
        String paneStatus = session.docking.activePaneId.get() == null
            ? ""
            : paneStatuses.getOrDefault(session.docking.activePaneId.get(), "");
        long visible = session.points.entries.stream().filter(ax.xz.mri.ui.model.IsochromatEntry::visible).count();
        shellStatus.set(String.format(
            "Scenario: %s | Iter: %s | Cursor: %.1f \u03bcs | Points: %d (%d visible) | Active: %s%s",
            scenario,
            iter,
            session.viewport.tC.get(),
            session.points.entries.size(),
            visible,
            activePane,
            paneStatus == null || paneStatus.isBlank() ? "" : " | " + paneStatus
        ));
    }

    private void applyLayout(WorkbenchLayoutState layoutState) {
        currentLayout = layoutState;
        for (var paneId : new ArrayList<>(floatingStages.keySet())) {
            dockPane(paneId);
        }
        dockSlots.clear();
        dockContainer.setCenter(buildDockNode(layoutState.dockRoot()));

        for (var paneId : PaneId.values()) {
            dockPane(paneId);
        }
        for (var floatingWindow : layoutState.floatingWindows()) {
            floatPane(floatingWindow.paneId());
            var stage = floatingStages.get(floatingWindow.paneId());
            if (stage != null) {
                stage.setX(floatingWindow.x());
                stage.setY(floatingWindow.y());
                stage.setWidth(floatingWindow.width());
                stage.setHeight(floatingWindow.height());
            }
        }
        activatePane(PaneId.SPHERE);
    }

    private Node buildDockNode(DockNode node) {
        if (node instanceof PaneLeaf leaf) {
            var slot = new StackPane();
            slot.getStyleClass().add("dock-slot");
            dockSlots.put(leaf.paneId(), slot);
            slot.getChildren().setAll(panes.get(leaf.paneId()));
            return slot;
        }
        if (node instanceof SplitNode splitNode) {
            var splitPane = new SplitPane();
            splitPane.setOrientation(splitNode.orientation());
            splitPane.getItems().addAll(buildDockNode(splitNode.first()), buildDockNode(splitNode.second()));
            splitPane.setDividerPositions(splitNode.dividerPosition());
            return splitPane;
        }
        if (node instanceof TabNode tabNode) {
            var tabPane = new TabPane();
            for (var child : tabNode.tabs()) {
                var tab = new Tab();
                tab.setClosable(false);
                tab.setContent(buildDockNode(child));
                tabPane.getTabs().add(tab);
            }
            tabPane.getSelectionModel().select(Math.max(0, Math.min(tabNode.selectedIndex(), tabPane.getTabs().size() - 1)));
            return tabPane;
        }
        throw new IllegalStateException("Unknown dock node: " + node);
    }

    private WorkbenchLayoutState snapshotLayout() {
        return new WorkbenchLayoutState(
            snapshotDockNode(currentLayout.dockRoot(), dockContainer.getCenter()),
            floatingStages.entrySet().stream()
                .map(entry -> new FloatingWindowState(
                    entry.getKey(),
                    entry.getValue().getX(),
                    entry.getValue().getY(),
                    entry.getValue().getWidth(),
                    entry.getValue().getHeight()
                ))
                .toList()
        );
    }

    private DockNode snapshotDockNode(DockNode template, Node actualNode) {
        if (template instanceof PaneLeaf) return template;
        if (template instanceof SplitNode splitNode && actualNode instanceof SplitPane splitPane) {
            var items = splitPane.getItems();
            return new SplitNode(
                splitPane.getOrientation(),
                splitPane.getDividerPositions().length > 0 ? splitPane.getDividerPositions()[0] : splitNode.dividerPosition(),
                snapshotDockNode(splitNode.first(), items.get(0)),
                snapshotDockNode(splitNode.second(), items.get(1))
            );
        }
        if (template instanceof TabNode tabNode && actualNode instanceof TabPane tabPane) {
            var tabs = new ArrayList<DockNode>();
            for (int index = 0; index < tabNode.tabs().size(); index++) {
                tabs.add(snapshotDockNode(tabNode.tabs().get(index), tabPane.getTabs().get(index).getContent()));
            }
            return new TabNode(tabs, tabPane.getSelectionModel().getSelectedIndex());
        }
        return template;
    }

    private Node buildFloatedPlaceholder(PaneId paneId) {
        var label = new Label(paneId.title() + " is floating");
        var button = new Button("Dock Pane");
        button.setOnAction(event -> dockPane(paneId));
        var box = new javafx.scene.layout.VBox(8, label, button);
        box.setAlignment(javafx.geometry.Pos.CENTER);
        box.getStyleClass().add("floating-placeholder");
        return box;
    }

    private WorkbenchLayoutState defaultLayout() {
        DockNode root = new SplitNode(
            Orientation.VERTICAL,
            0.55,
            new SplitNode(
                Orientation.HORIZONTAL,
                0.58,
                new PaneLeaf(PaneId.SPHERE),
                new SplitNode(
                    Orientation.VERTICAL,
                    0.65,
                    new PaneLeaf(PaneId.GEOMETRY),
                    new PaneLeaf(PaneId.POINTS)
                )
            ),
            new SplitNode(
                Orientation.VERTICAL,
                0.35,
                new PaneLeaf(PaneId.TIMELINE),
                new SplitNode(
                    Orientation.HORIZONTAL,
                    0.42,
                    new SplitNode(
                        Orientation.HORIZONTAL,
                        0.5,
                        new PaneLeaf(PaneId.PHASE_MAP_Z),
                        new PaneLeaf(PaneId.PHASE_MAP_R)
                    ),
                    new SplitNode(
                        Orientation.HORIZONTAL,
                        0.34,
                        new PaneLeaf(PaneId.TRACE_PHASE),
                        new SplitNode(
                            Orientation.HORIZONTAL,
                            0.5,
                            new PaneLeaf(PaneId.TRACE_POLAR),
                            new PaneLeaf(PaneId.TRACE_MAGNITUDE)
                        )
                    )
                )
            )
        );
        return new WorkbenchLayoutState(root, List.of());
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
