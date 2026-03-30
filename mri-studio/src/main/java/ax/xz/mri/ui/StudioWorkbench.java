package ax.xz.mri.ui;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.service.io.BlochDataReader;
import ax.xz.mri.state.AppState;
import ax.xz.mri.state.CrossSectionState.ShadeMode;
import ax.xz.mri.ui.pane.*;
import ax.xz.mri.ui.theme.StudioTheme;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.*;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.io.File;
import java.util.List;

/**
 * Main application window: menu bar, toolbar, and SplitPane layout of all six panes.
 * Layout (roughly):
 *   ┌─────────────────────────────────────────────────────┐
 *   │ MenuBar                                              │
 *   │ ToolBar                                              │
 *   ├──────────────────────────┬──────────────────────────┤
 *   │                          │ CrossSectionPane          │
 *   │  SpherePane (large)      ├──────────────────────────┤
 *   │                          │ IsochromatListPane        │
 *   ├──────────────────────────┴──────────────────────────┤
 *   │ TimelinePane                                         │
 *   ├──────────────────────────┬──────────────────────────┤
 *   │ PhaseMapsPane            │ AnglePlotsPane            │
 *   └──────────────────────────┴──────────────────────────┘
 */
public class StudioWorkbench extends BorderPane {

    private final AppState state = new AppState();

    public StudioWorkbench() {
        setBackground(new Background(new BackgroundFill(StudioTheme.BG, null, null)));
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
        var open = new MenuItem("Open…");
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

        var bar = new MenuBar(fileMenu, viewMenu);
        bar.setStyle("-fx-background-color: #0d0d18; -fx-border-color: #1a1a28; -fx-border-width: 0 0 1 0;");
        return bar;
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
        iterLabel.setTextFill(StudioTheme.TX2);
        iterLabel.setFont(StudioTheme.MONO_8);
        state.document.iterationKeys.addListener((javafx.collections.ListChangeListener<String>) c -> {
            int n = state.document.iterationKeys.size();
            iterSlider.setMax(Math.max(0, n - 1));
            iterSlider.setDisable(n <= 1);
        });
        iterSlider.valueProperty().addListener((obs, old, v) -> {
            int idx = (int) Math.round(v.doubleValue());
            state.document.iterationIndex.set(idx);
            iterLabel.setText(state.document.iterationKeys.isEmpty() ? "—"
                : state.document.iterationKeys.get(Math.min(idx, state.document.iterationKeys.size() - 1)));
        });

        // Shade mode
        var shadeCb = new ComboBox<String>();
        shadeCb.getItems().addAll("|M⊥|", "Signal");
        shadeCb.setValue("|M⊥|");
        shadeCb.setOnAction(e -> state.crossSection.shadeMode.set(
            "Signal".equals(shadeCb.getValue()) ? ShadeMode.SIGNAL : ShadeMode.MP));

        // Show |M⊥| projection
        var showMpChk = new CheckBox("|M⊥|");
        showMpChk.setTextFill(StudioTheme.TX2);
        showMpChk.selectedProperty().bindBidirectional(state.crossSection.showMpProj);

        style(scenarioBox); style(shadeCb);
        style(iterSlider);

        var bar = new HBox(6,
            label("Scenario:"), scenarioBox,
            label("Iter:"), iterSlider, iterLabel,
            new Separator(Orientation.VERTICAL),
            label("Shade:"), shadeCb,
            showMpChk);
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        bar.setPadding(new Insets(3, 6, 3, 6));
        bar.setBackground(new Background(new BackgroundFill(
            Color.web("#0d0d18"), null, null)));
        bar.setBorder(new Border(new BorderStroke(Color.web("#1a1a28"),
            BorderStrokeStyle.SOLID, null, new BorderWidths(0, 0, 1, 0))));
        return bar;
    }

    // ── Main layout ──────────────────────────────────────────────────────────

    private SplitPane buildMain() {
        // Right side of top half: cross-section over iso list
        var crossSection = new CrossSectionPane(state);
        var isoList      = new IsochromatListPane(state);
        var rightTop     = new SplitPane(crossSection, isoList);
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

    // ── Styling helpers ───────────────────────────────────────────────────────

    private static Label label(String text) {
        var l = new Label(text);
        l.setTextFill(StudioTheme.TX2);
        l.setFont(StudioTheme.MONO_8);
        return l;
    }

    private static void style(Control c) {
        c.setStyle("""
            -fx-background-color: #101018;
            -fx-text-fill: #94a3b8;
            -fx-font-family: monospace;
            -fx-font-size: 9px;
            -fx-background-radius: 0;
            -fx-border-radius: 0;
            -fx-border-color: #1a1a28;
            -fx-border-width: 1;
            """);
    }
}
