package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import ax.xz.mri.ui.workbench.pane.config.ConfigStore;
import ax.xz.mri.ui.workbench.pane.config.GeometryPreview;
import ax.xz.mri.ui.workbench.pane.config.NumberField;
import ax.xz.mri.ui.workbench.pane.config.RelaxationPreview;
import ax.xz.mri.ui.workbench.pane.schematic.CircuitEditSession;
import ax.xz.mri.ui.workbench.pane.schematic.SchematicPane;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Tabbed editor for a {@link SimulationConfigDocument}.
 *
 * <p>Tabs:
 * <ul>
 *   <li>Overview — live metrics + health checks.</li>
 *   <li>Tissue — T1/T2 and gyromagnetic ratio.</li>
 *   <li>Geometry — FOV + grid size + slice thickness.</li>
 *   <li>Reference — reference B0 + integration step.</li>
 *   <li>Schematic — the {@link SchematicPane} editing the associated circuit.</li>
 * </ul>
 *
 * <p>{@link ConfigStore} is the single source of truth; every control binds
 * bidirectionally. The schematic tab's {@link CircuitEditSession} shares the
 * same repository via the pane context, so mutations land back on the
 * {@link CircuitDocument} immediately.
 */
public final class SimulationConfigEditorPane extends WorkbenchPane {
    private static final int MAX_UNDO = 100;

    private final SimulationConfigDocument document;
    private final ConfigStore store;
    private SimulationConfig savedConfig;
    private ax.xz.mri.model.circuit.CircuitDocument savedCircuit;

    private final Deque<SimulationConfig> undoStack = new ArrayDeque<>();
    private final Deque<SimulationConfig> redoStack = new ArrayDeque<>();

    private final SimpleStringProperty nameProperty = new SimpleStringProperty();
    private final Label titleLabel = new Label();
    private final Label dirtyPill = new Label("UNSAVED");
    private final Label footerStatus = new Label();
    private final Button undoButton = new Button("\u21B6");
    private final Button redoButton = new Button("\u21B7");
    private final Button saveButton = new Button("Save");
    private final Button revertButton = new Button("Revert");

    private final TabPane tabs = new TabPane();
    private final Tab overviewTab = new Tab("Overview");
    private final Tab tissueTab = new Tab("Tissue");
    private final Tab geometryTab = new Tab("Geometry");
    private final Tab referenceTab = new Tab("Reference");
    private final Tab schematicTab = new Tab("Schematic");

    private CircuitEditSession circuitSession;
    private SchematicPane schematicPane;
    private Runnable onTitleChanged;

    public SimulationConfigEditorPane(PaneContext paneContext, SimulationConfigDocument document) {
        super(paneContext);
        this.document = document;
        this.savedConfig = document.config();
        this.nameProperty.set(document.name());
        setPaneTitle("Config: " + document.name());
        this.store = new ConfigStore(document.config());

        buildShell();

        overviewTab.setContent(scrollWrap(buildOverviewTab()));
        tissueTab.setContent(scrollWrap(buildTissueTab()));
        geometryTab.setContent(scrollWrap(buildGeometryTab()));
        referenceTab.setContent(scrollWrap(buildReferenceTab()));
        schematicTab.setContent(buildSchematicTab());

        store.config.addListener((obs, oldC, newC) -> onConfigChanged(oldC, newC));
    }

    public SimulationConfigDocument document() { return document; }

    public void setOnTitleChanged(Runnable listener) { this.onTitleChanged = listener; }

    public boolean isDirty() {
        if (!Objects.equals(store.getConfig(), savedConfig)) return true;
        if (circuitSession != null) {
            var live = circuitSession.doc();
            if (live != null && !live.id().value().equals("circuit-placeholder")
                    && !Objects.equals(live, savedCircuit)) return true;
        }
        return false;
    }

    public void save() {
        var repo = paneContext.session().project.repository.get();
        var next = store.getConfig();
        boolean configChanged = !Objects.equals(next, savedConfig);
        boolean circuitChanged = circuitSession != null
            && circuitSession.doc() != null
            && !circuitSession.doc().id().value().equals("circuit-placeholder")
            && !Objects.equals(circuitSession.doc(), savedCircuit);
        if (!configChanged && !circuitChanged) return;

        if (configChanged) {
            var simConfig = document.withConfig(next);
            repo.addSimConfig(simConfig);
            savedConfig = next;
        }
        if (circuitChanged) {
            var liveCircuit = circuitSession.doc();
            if (repo.circuit(liveCircuit.id()) != null) {
                repo.updateCircuit(liveCircuit);
            } else {
                repo.addCircuit(liveCircuit);
            }
            savedCircuit = liveCircuit;
        }
        paneContext.session().project.explorer.refresh();
        paneContext.session().project.saveProjectQuietly();
        refreshDirty();
        if (onTitleChanged != null) onTitleChanged.run();
    }

    public void revert() {
        store.setConfig(savedConfig);
        if (circuitSession != null && savedCircuit != null
                && !Objects.equals(circuitSession.doc(), savedCircuit)) {
            circuitSession.loadDocument(savedCircuit);
        }
        refreshDirty();
    }

    public void undo() {
        if (undoStack.isEmpty()) return;
        var snapshot = undoStack.pop();
        redoStack.push(store.getConfig());
        store.setConfig(snapshot);
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        var snapshot = redoStack.pop();
        undoStack.push(store.getConfig());
        store.setConfig(snapshot);
    }

    // ───────── Chrome ─────────

    private void buildShell() {
        var root = new BorderPane();
        root.getStyleClass().add("cfg-editor");
        root.setTop(buildTitleStrip());
        root.setCenter(buildTabs());
        root.setBottom(buildFooter());
        root.setFocusTraversable(true);
        root.setOnKeyPressed(this::onShortcut);
        setPaneContent(root);
        refreshDirty();
    }

    private Node buildTitleStrip() {
        titleLabel.getStyleClass().add("cfg-title");
        titleLabel.textProperty().bind(nameProperty);
        titleLabel.setCursor(javafx.scene.Cursor.TEXT);
        Tooltip.install(titleLabel, new Tooltip("Double-click to rename"));
        titleLabel.setOnMouseClicked(e -> { if (e.getClickCount() == 2) beginRename(); });

        var typeLabel = new Label("Simulation configuration");
        typeLabel.getStyleClass().add("cfg-title-meta");
        dirtyPill.getStyleClass().add("cfg-title-dirty-pill");

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var stats = new HBox(16);
        stats.setAlignment(Pos.CENTER_LEFT);
        stats.getChildren().addAll(
            stat("LARMOR",   fmt(store.larmorHz, SimulationConfigEditorPane::formatFrequencyShort)),
            stat("dt",       fmt(store.dtSeconds, SimulationConfigEditorPane::formatSeconds)),
            stat("GRID",     Bindings.createStringBinding(() -> store.nZ.get() + "\u00D7" + store.nR.get(), store.nZ, store.nR))
        );

        var strip = new HBox(10, titleLabel, typeLabel, dirtyPill, spacer, stats);
        strip.getStyleClass().add("cfg-title-strip");
        strip.setAlignment(Pos.CENTER_LEFT);
        return strip;
    }

    private Node stat(String label, javafx.beans.value.ObservableValue<String> binding) {
        var l = new Label(label);
        l.getStyleClass().add("cfg-title-stat-label");
        var v = new Label();
        v.getStyleClass().add("cfg-title-stat-value");
        v.textProperty().bind(binding);
        var box = new VBox(0, l, v);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Node buildTabs() {
        tabs.getStyleClass().add("cfg-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        for (var t : List.of(overviewTab, tissueTab, geometryTab, referenceTab, schematicTab)) t.setClosable(false);
        tabs.getTabs().addAll(overviewTab, tissueTab, geometryTab, referenceTab, schematicTab);
        return tabs;
    }

    private Node buildFooter() {
        undoButton.getStyleClass().add("icon-button");
        undoButton.setOnAction(e -> undo());
        redoButton.getStyleClass().add("icon-button");
        redoButton.setOnAction(e -> redo());
        var undoGroup = new HBox(undoButton, redoButton);
        undoGroup.getStyleClass().add("cfg-footer-group");

        footerStatus.getStyleClass().add("cfg-footer-status");

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        revertButton.getStyleClass().addAll("button", "ghost");
        revertButton.setOnAction(e -> revert());
        saveButton.getStyleClass().addAll("button", "primary");
        saveButton.setOnAction(e -> save());

        var footer = new HBox(10, undoGroup, footerStatus, spacer, revertButton, saveButton);
        footer.getStyleClass().add("cfg-footer");
        return footer;
    }

    private void onShortcut(javafx.scene.input.KeyEvent event) {
        if (new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN).match(event)) {
            save(); event.consume();
        } else if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN).match(event)) {
            redo(); event.consume();
        } else if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN).match(event)) {
            undo(); event.consume();
        }
    }

    private ScrollPane scrollWrap(Node content) {
        var box = new VBox(content);
        box.getStyleClass().add("cfg-tab-inner");
        var scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        return scroll;
    }

    // ───────── Tabs ─────────

    private Node buildOverviewTab() {
        var box = new VBox(10);
        var metrics = new HBox();
        metrics.getStyleClass().add("cfg-metric-strip");
        metrics.setFillHeight(true);
        metrics.getChildren().addAll(
            bigMetric("REF B\u2080", fmt(store.referenceB0Tesla, SimulationConfigEditorPane::formatTesla), "T", false),
            bigMetric("LARMOR", fmt(store.larmorHz, SimulationConfigEditorPane::formatFrequencyShort),
                fmt(store.larmorHz, SimulationConfigEditorPane::frequencyUnit), false),
            bigMetric("TIME STEP", fmt(store.dtSeconds, SimulationConfigEditorPane::formatDt),
                fmt(store.dtSeconds, SimulationConfigEditorPane::dtUnit), false),
            bigMetric("GRID",
                Bindings.createStringBinding(() -> store.nZ.get() + "\u00D7" + store.nR.get(), store.nZ, store.nR),
                staticText(""), true)
        );
        box.getChildren().add(metrics);

        box.getChildren().add(sectionTitle("Configuration at a glance"));
        box.getChildren().addAll(
            kvBound("Tissue", Bindings.createStringBinding(
                () -> String.format("T\u2081 = %s \u00b7 T\u2082 = %s", formatMs(store.t1Ms.get()), formatMs(store.t2Ms.get())),
                store.t1Ms, store.t2Ms)),
            kvBound("Spatial FOV", Bindings.createStringBinding(
                () -> String.format("%.1f \u00D7 %.1f mm", store.fovZMm.get(), store.fovRMm.get()),
                store.fovZMm, store.fovRMm)),
            kvBound("Slice half-thickness", fmt(store.sliceHalfMm, v -> String.format("%.2f mm", v))),
            kvBound("Gyromagnetic ratio",
                fmt(store.gamma, v -> String.format("%.3f \u00D7 10\u2076 rad/s/T", v / 1e6))),
            kvBound("Reference period", fmt(store.larmorHz, v -> formatSeconds(1.0 / Math.max(v, 1e-12)))),
            kvBound("Circuit", Bindings.createStringBinding(
                () -> {
                    var id = store.circuitId.get();
                    if (id == null) return "(none)";
                    var repo = paneContext.session().project.repository.get();
                    var doc = repo.circuit(id);
                    return doc == null ? "(missing)" : doc.name();
                },
                store.circuitId))
        );
        return box;
    }

    private Node buildTissueTab() {
        var box = new VBox(10);
        box.getChildren().add(sectionTitle("Relaxation"));
        box.getChildren().add(rowLabelled("T\u2081", numberField(0, 1_000_000, 10).bindBidirectional(store.t1Ms), "ms"));
        box.getChildren().add(rowLabelled("T\u2082", numberField(0, 1_000_000, 5).bindBidirectional(store.t2Ms), "ms"));

        box.getChildren().add(new Separator());
        box.getChildren().add(sectionTitle("Nucleus"));
        box.getChildren().add(rowLabelled("\u03b3", numberField(0, 1e12, 1e6).bindBidirectional(store.gamma), "rad/s/T"));

        var preview = new RelaxationPreview();
        preview.setParams(store.t1Ms.get(), store.t2Ms.get());
        Runnable refreshPreview = () -> preview.setParams(store.t1Ms.get(), store.t2Ms.get());
        store.t1Ms.addListener((obs, o, n) -> refreshPreview.run());
        store.t2Ms.addListener((obs, o, n) -> refreshPreview.run());
        box.getChildren().add(preview);
        return box;
    }

    private Node buildGeometryTab() {
        var box = new VBox(10);
        box.getChildren().add(sectionTitle("Spatial grid"));
        box.getChildren().add(rowLabelled("Slice half-thickness", numberField(0.1, 100, 0.5).bindBidirectional(store.sliceHalfMm), "mm"));
        box.getChildren().add(rowLabelled("FOV Z", numberField(1, 2000, 1).bindBidirectional(store.fovZMm), "mm"));
        box.getChildren().add(rowLabelled("FOV R", numberField(1, 2000, 1).bindBidirectional(store.fovRMm), "mm"));
        box.getChildren().add(rowLabelled("n_Z", numberField(2, 10000, 1).decimals(0).bindBidirectional(store.nZ), "samples"));
        box.getChildren().add(rowLabelled("n_R", numberField(2, 10000, 1).decimals(0).bindBidirectional(store.nR), "samples"));

        var preview = new GeometryPreview();
        preview.setGeometry(store.fovZMm.get(), store.fovRMm.get(), store.nZ.get(), store.nR.get(), store.sliceHalfMm.get());
        Runnable repaint = () -> preview.setGeometry(store.fovZMm.get(), store.fovRMm.get(),
            store.nZ.get(), store.nR.get(), store.sliceHalfMm.get());
        store.fovZMm.addListener((obs, o, n) -> repaint.run());
        store.fovRMm.addListener((obs, o, n) -> repaint.run());
        store.nZ.addListener((obs, o, n) -> repaint.run());
        store.nR.addListener((obs, o, n) -> repaint.run());
        store.sliceHalfMm.addListener((obs, o, n) -> repaint.run());
        box.getChildren().add(preview);
        return box;
    }

    private Node buildReferenceTab() {
        var box = new VBox(10);
        box.getChildren().add(sectionTitle("Rotating frame"));
        box.getChildren().add(rowLabelled("Reference B\u2080", numberField(-50, 50, 0.001).bindBidirectional(store.referenceB0Tesla), "T"));

        // dt has its own setup so we can guard against zero/negative.
        var dt = numberField(1e-12, 1e-2, 1e-7);
        dt.setValue(store.dtSeconds.get());
        dt.valueProperty().addListener((obs, o, n) -> { if (n != null && n.doubleValue() > 0) store.dtSeconds.set(n.doubleValue()); });
        store.dtSeconds.addListener((obs, o, n) -> dt.setValueQuiet(n.doubleValue()));
        box.getChildren().add(rowLabelled("Time step dt", dt, "s"));

        var larmor = new Label();
        larmor.textProperty().bind(fmt(store.larmorHz, v -> String.format("\u03c9\u209b / 2\u03c0 = %s", formatFrequencyShort(v) + frequencyUnit(v))));
        larmor.getStyleClass().add("cfg-row-hint");
        box.getChildren().add(larmor);

        var nyquist = new Label();
        nyquist.textProperty().bind(fmt(store.nyquistHz, v -> String.format("Nyquist = %s", formatFrequencyShort(v) + frequencyUnit(v))));
        nyquist.getStyleClass().add("cfg-row-hint");
        box.getChildren().add(nyquist);

        return box;
    }

    private Node buildSchematicTab() {
        var repo = paneContext.session().project.repository.get();
        var id = store.circuitId.get();
        CircuitDocument doc = id == null ? null : repo.circuit(id);
        if (doc == null) {
            // Config points at a missing circuit — construct an empty placeholder so the pane still renders.
            doc = CircuitDocument.empty(new ProjectNodeId("circuit-placeholder"), "(no circuit)");
        }
        circuitSession = new CircuitEditSession(doc);
        savedCircuit = doc;
        // Schematic edits auto-commit in memory so downstream consumers
        // (SimulationOutputFactory → SignalTraceComputer etc.) see the latest
        // circuit immediately. The dirty pill still tracks whether the
        // committed state has been written to disk — see save().
        circuitSession.current.addListener((obs, oldDoc, newDoc) -> {
            refreshDirty();
            if (newDoc == null || newDoc.id().value().equals("circuit-placeholder")) return;
            var liveRepo = paneContext.session().project.repository.get();
            if (liveRepo.circuit(newDoc.id()) != null) liveRepo.updateCircuit(newDoc);
            else liveRepo.addCircuit(newDoc);
            paneContext.session().project.explorer.markContentChanged();
        });

        schematicPane = new SchematicPane(circuitSession,
            () -> paneContext.session().project.repository.get(),
            eigenfieldId -> paneContext.session().project.openNode(eigenfieldId));
        return schematicPane;
    }

    /** Switch the editor's tab control to the Schematic tab. */
    public void selectSchematicTab() {
        tabs.getSelectionModel().select(schematicTab);
    }

    /**
     * The {@link SchematicPane} embedded in the Schematic tab. Lazily created
     * the first time {@link #buildSchematicTab()} runs — null until the
     * editor is fully constructed (it is, by the time the constructor
     * returns, since {@code buildSchematicTab} is called in the ctor).
     */
    public SchematicPane schematicPane() { return schematicPane; }

    // ───────── Helpers ─────────

    private void onConfigChanged(SimulationConfig oldC, SimulationConfig newC) {
        if (oldC != null && !Objects.equals(oldC, newC)) {
            undoStack.push(oldC);
            while (undoStack.size() > MAX_UNDO) undoStack.pollLast();
            redoStack.clear();
        }
        refreshDirty();
    }

    private void refreshDirty() {
        boolean dirty = isDirty();
        dirtyPill.setVisible(dirty);
        dirtyPill.setManaged(dirty);
        saveButton.setDisable(!dirty);
        revertButton.setDisable(!dirty);
        undoButton.setDisable(undoStack.isEmpty());
        redoButton.setDisable(redoStack.isEmpty());
        footerStatus.setText(dirty ? "Unsaved changes" : "Saved");
    }

    private void beginRename() {
        var dialog = new javafx.scene.control.TextInputDialog(nameProperty.get());
        dialog.setTitle("Rename configuration");
        dialog.setHeaderText("Rename the simulation configuration");
        dialog.setContentText("Name:");
        dialog.showAndWait().map(String::trim).filter(v -> !v.isBlank()).ifPresent(v -> {
            nameProperty.set(v);
            paneContext.session().project.renameSimConfig(document.id(), v);
        });
    }

    private Node sectionTitle(String text) {
        var l = new Label(text);
        l.getStyleClass().add("cfg-section-title");
        return l;
    }

    private Node rowLabelled(String label, Node control, String unit) {
        var l = new Label(label);
        l.getStyleClass().add("cfg-row-label");
        l.setPrefWidth(160);
        var row = new HBox(8, l, control);
        row.getStyleClass().add("cfg-row");
        row.setAlignment(Pos.CENTER_LEFT);
        if (unit != null && !unit.isEmpty()) {
            var u = new Label(unit);
            u.getStyleClass().add("cfg-row-unit");
            row.getChildren().add(u);
        }
        return row;
    }

    private Node kvBound(String label, javafx.beans.value.ObservableValue<String> value) {
        var l = new Label(label);
        l.getStyleClass().add("cfg-kv-label");
        var v = new Label();
        v.getStyleClass().add("cfg-kv-value");
        v.textProperty().bind(value);
        var row = new HBox(8, l, v);
        row.getStyleClass().add("cfg-kv");
        return row;
    }

    private Node bigMetric(String label, javafx.beans.value.ObservableValue<String> value,
                           javafx.beans.value.ObservableValue<String> unit, boolean last) {
        var l = new Label(label);
        l.getStyleClass().add("cfg-metric-label");
        var v = new Label();
        v.getStyleClass().add("cfg-metric-value");
        v.textProperty().bind(value);
        var u = new Label();
        u.getStyleClass().add("cfg-metric-unit");
        u.textProperty().bind(unit);
        var valueRow = new HBox(0, v, u);
        valueRow.setAlignment(Pos.BASELINE_LEFT);
        var box = new VBox(0, l, valueRow);
        box.getStyleClass().add("cfg-metric");
        if (last) box.getStyleClass().add("last");
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private Node bigMetric(String label, javafx.beans.value.ObservableValue<String> value, String unit, boolean last) {
        return bigMetric(label, value, new SimpleStringProperty(unit), last);
    }

    private NumberField numberField(double min, double max, double step) {
        return new NumberField().range(min, max).step(step);
    }

    private static javafx.beans.value.ObservableValue<String> fmt(
        javafx.beans.value.ObservableNumberValue prop, Function<Double, String> f) {
        return Bindings.createStringBinding(() -> f.apply(prop.doubleValue()), prop);
    }

    private static javafx.beans.value.ObservableValue<String> staticText(String text) {
        return new SimpleStringProperty(text);
    }

    private static String formatTesla(double v) {
        return String.format("%.4f", v);
    }

    private static String formatFrequencyShort(double hz) {
        double abs = Math.abs(hz);
        if (abs == 0) return "0";
        if (abs >= 1e9) return String.format("%.2f", hz / 1e9);
        if (abs >= 1e6) return String.format("%.2f", hz / 1e6);
        if (abs >= 1e3) return String.format("%.2f", hz / 1e3);
        return String.format("%.2f", hz);
    }

    private static String frequencyUnit(double hz) {
        double abs = Math.abs(hz);
        if (abs >= 1e9) return " GHz";
        if (abs >= 1e6) return " MHz";
        if (abs >= 1e3) return " kHz";
        return " Hz";
    }

    private static String formatSeconds(double s) {
        double abs = Math.abs(s);
        if (abs == 0) return "0";
        if (abs >= 1) return String.format("%.3f s", s);
        if (abs >= 1e-3) return String.format("%.3f ms", s * 1e3);
        if (abs >= 1e-6) return String.format("%.3f \u03bcs", s * 1e6);
        return String.format("%.3f ns", s * 1e9);
    }

    private static String formatMs(double v) {
        return String.format("%.0f ms", v);
    }

    private static String formatDt(double s) {
        double abs = Math.abs(s);
        if (abs >= 1e-3) return String.format("%.3f", s * 1e3);
        if (abs >= 1e-6) return String.format("%.3f", s * 1e6);
        return String.format("%.3f", s * 1e9);
    }

    private static String dtUnit(double s) {
        double abs = Math.abs(s);
        if (abs >= 1e-3) return " ms";
        if (abs >= 1e-6) return " \u03bcs";
        return " ns";
    }

    public SimulationConfig currentConfig() {
        return store.getConfig();
    }
}
