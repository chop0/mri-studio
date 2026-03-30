package ax.xz.mri.ui;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.service.io.BlochDataReader;
import ax.xz.mri.state.AppState;
import ax.xz.mri.ui.pane.*;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;

import java.io.File;

/**
 * Main application window: menu bar, toolbar, and SplitPane layout of all panes.
 * Layout (roughly):
 *   +-----------------------------------------------------+
 *   | MenuBar                                              |
 *   | ToolBar (scenario, iteration)                        |
 *   +---------------------------+--------------------------+
 *   |                           | GeometryViewPane          |
 *   |  SpherePane (large)       +-------------------------+
 *   |                           | PointsOfInterestPane     |
 *   +---------------------------+--------------------------+
 *   | TimelinePane                                         |
 *   +---------------------------+--------------------------+
 *   | PhaseMapsPane             | AnglePlotsPane            |
 *   +---------------------------+--------------------------+
 */
public class StudioWorkbench extends BorderPane {

    private final AppState state = new AppState();

    public StudioWorkbench() {
        setTop(buildTop());
        setCenter(buildMain());
        installDropTarget();
    }

    // ── Top bar ──────────────────────────────────────────────────────────────

    private VBox buildTop() {
        var menuBar = buildMenuBar();
        var toolBar = buildToolBar();
        return new VBox(menuBar, toolBar);
    }

    private MenuBar buildMenuBar() {
        var open = new MenuItem("Open\u2026");
        open.setOnAction(e -> {
            var fc = new javafx.stage.FileChooser();
            fc.setTitle("Open bloch_data.json");
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("JSON", "*.json"));
            var f = fc.showOpenDialog(getScene().getWindow());
            if (f != null) loadFile(f);
        });
        var exit = new MenuItem("Exit");
        exit.setOnAction(e -> javafx.application.Platform.exit());
        var fileMenu = new Menu("File", null, open, new SeparatorMenuItem(), exit);

        var resetLayout = new MenuItem("Reset Layout");
        resetLayout.setDisable(true);
        var viewMenu = new Menu("View", null, resetLayout);

        return new MenuBar(fileMenu, viewMenu);
    }

    private HBox buildToolBar() {
        // Scenario selector
        var scenarioBox = new ComboBox<String>();
        scenarioBox.setPromptText("scenario");
        scenarioBox.setPrefWidth(130);
        state.document.blochData.addListener((obs, old, data) -> {
            scenarioBox.getItems().clear();
            if (data != null) {
                var keys = data.scenarios().keySet().stream().sorted().toList();
                scenarioBox.getItems().addAll(keys);
                if (!keys.isEmpty()) scenarioBox.setValue(keys.get(0));
            }
        });
        scenarioBox.setOnAction(e -> {
            String v = scenarioBox.getValue();
            if (v != null) state.document.currentScenario.set(v);
        });

        // Iteration slider
        var iterSlider = new Slider(0, 1, 0);
        iterSlider.setPrefWidth(120);
        iterSlider.setMajorTickUnit(1); iterSlider.setSnapToTicks(true);
        var iterLabel = new Label("0");
        state.document.iterationKeys.addListener((javafx.collections.ListChangeListener<String>) c -> {
            int n = state.document.iterationKeys.size();
            iterSlider.setMax(Math.max(0, n - 1));
            iterSlider.setDisable(n <= 1);
        });
        iterSlider.valueProperty().addListener((obs, old, v) -> {
            int idx = (int) Math.round(v.doubleValue());
            state.document.iterationIndex.set(idx);
            iterLabel.setText(state.document.iterationKeys.isEmpty() ? "\u2014"
                : state.document.iterationKeys.get(Math.min(idx, state.document.iterationKeys.size() - 1)));
        });

        var bar = new HBox(6,
            new Label("Scenario:"), scenarioBox,
            new Label("Iter:"), iterSlider, iterLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(3, 6, 3, 6));
        return bar;
    }

    // ── Main layout ──────────────────────────────────────────────────────────

    private SplitPane buildMain() {
        // Right side of top half: geometry over points list
        var geometryView = new GeometryViewPane(state);
        var pointsList   = new PointsOfInterestPane(state);
        var rightTop     = new SplitPane(geometryView, pointsList);
        rightTop.setOrientation(Orientation.VERTICAL);
        rightTop.setDividerPositions(0.65);

        // Top half: sphere | right panel
        var sphere    = new SpherePane(state);
        var topSplit  = new SplitPane(sphere, rightTop);
        topSplit.setDividerPositions(0.58);

        // Bottom area
        var timeline    = new TimelinePane(state);
        var phaseMaps   = new PhaseMapsPane(state);
        var anglePlots  = new AnglePlotsPane(state);
        var bottomRight = new SplitPane(phaseMaps, anglePlots);
        bottomRight.setDividerPositions(0.5);
        var bottomSplit = new SplitPane(timeline, bottomRight);
        bottomSplit.setOrientation(Orientation.VERTICAL);
        bottomSplit.setDividerPositions(0.35);

        // Main vertical split
        var main = new SplitPane(topSplit, bottomSplit);
        main.setOrientation(Orientation.VERTICAL);
        main.setDividerPositions(0.55);
        return main;
    }

    // ── File loading ─────────────────────────────────────────────────────────

    public void loadFile(File file) {
        try {
            BlochData data = BlochDataReader.read(file);
            state.document.blochData.set(data);
            state.isochromats.resetToDefaults();
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Failed to load: " + ex.getMessage()).showAndWait();
        }
    }

    private void installDropTarget() {
        setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        setOnDragDropped(e -> {
            var files = e.getDragboard().getFiles();
            if (!files.isEmpty()) loadFile(files.get(0));
            e.setDropCompleted(true); e.consume();
        });
    }
}
