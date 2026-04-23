package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.FieldDefinition;
import ax.xz.mri.model.simulation.ReceiveCoil;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.model.simulation.dsl.EigenfieldStarterLibrary;
import ax.xz.mri.model.simulation.dsl.ReceiveCoilStarterLibrary;
import ax.xz.mri.service.ObjectFactory;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import ax.xz.mri.ui.workbench.pane.config.ConfigStore;
import ax.xz.mri.ui.workbench.pane.config.GeometryPreview;
import ax.xz.mri.ui.workbench.pane.config.NumberField;
import ax.xz.mri.ui.workbench.pane.config.RelaxationPreview;
import ax.xz.mri.ui.workbench.pane.config.SegmentedControl;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.DoubleFunction;

/**
 * Dense, tabbed editor for a {@link SimulationConfigDocument}.
 *
 * <p>Architecture:
 * <ul>
 *   <li>{@link ConfigStore} is the single source of truth. Every control binds
 *       bidirectionally to a property on the store; every derived label uses a
 *       {@link javafx.beans.binding.StringBinding} off those properties.</li>
 *   <li>Tabs are built <em>once</em> in the constructor. User edits never
 *       trigger a rebuild — only {@link ConfigStore#config} updates propagate
 *       through the bindings.</li>
 *   <li>Undo/redo/revert stack stores immutable {@link SimulationConfig}
 *       snapshots and pushes them into {@link ConfigStore#setConfig}.</li>
 * </ul>
 */
public final class SimulationConfigEditorPane extends WorkbenchPane {
    private static final int MAX_UNDO = 100;

    // --- State ---
    private final ConfigStore store;
    private SimulationConfigDocument document;
    private SimulationConfig savedConfig;

    private final ArrayDeque<SimulationConfig> undoStack = new ArrayDeque<>();
    private final ArrayDeque<SimulationConfig> redoStack = new ArrayDeque<>();
    private Runnable onTitleChanged;

    private final StringProperty nameProperty = new SimpleStringProperty();

    // --- Chrome widgets that react via bindings ---
    private final Label titleLabel = new Label();
    private final Label dirtyPill = new Label("UNSAVED");
    private final Label footerStatus = new Label();
    private final Button undoButton = new Button("\u21B6");
    private final Button redoButton = new Button("\u21B7");
    private final Button saveButton = new Button("Save");
    private final Button revertButton = new Button("Revert");

    // --- Tabs ---
    private final TabPane tabs = new TabPane();
    private final Tab overviewTab = new Tab("Overview");
    private final Tab referenceTab = new Tab("Reference frame");
    private final Tab tissueTab = new Tab("Tissue");
    private final Tab geometryTab = new Tab("Geometry");
    private final Tab fieldsTab = new Tab("Fields");
    private final Tab receiveCoilsTab = new Tab("Receive coils");

    // --- Fields tab state ---
    private TableView<FieldDefinition> fieldsTable;
    private final ObjectProperty<FieldDefinition> selectedField = new SimpleObjectProperty<>();
    private VBox fieldsDetail;

    // --- Receive coils tab state ---
    private TableView<ReceiveCoil> receiveCoilsTable;
    private final ObjectProperty<ReceiveCoil> selectedReceiveCoil = new SimpleObjectProperty<>();
    private VBox receiveCoilsDetail;
    private final ObservableList<String> eigenfieldNames = FXCollections.observableArrayList();
    private final LinkedHashMap<String, ProjectNodeId> eigenfieldIdByName = new LinkedHashMap<>();

    public SimulationConfigEditorPane(PaneContext paneContext, SimulationConfigDocument document) {
        super(paneContext);
        this.document = document;
        this.savedConfig = document.config();
        this.nameProperty.set(document.name());
        setPaneTitle("Config: " + document.name());

        this.store = new ConfigStore(document.config());

        buildShell();
        bindChrome();

        overviewTab.setContent(scrollWrap(buildOverviewTab()));
        referenceTab.setContent(scrollWrap(buildReferenceTab()));
        tissueTab.setContent(scrollWrap(buildTissueTab()));
        geometryTab.setContent(scrollWrap(buildGeometryTab()));
        fieldsTab.setContent(scrollWrap(buildFieldsTab()));
        receiveCoilsTab.setContent(scrollWrap(buildReceiveCoilsTab()));

        // Persist + notify every time the authoritative config changes.
        store.config.addListener((obs, oldC, newC) -> onConfigChanged(oldC, newC));

        // Eigenfield rename / add / remove elsewhere -> refresh our combo model.
        paneContext.session().project.explorer.structureRevision.addListener((obs, o, n) -> refreshEigenfields());
        refreshEigenfields();
    }

    // ================================================================
    // Chrome
    // ================================================================

    private void buildShell() {
        var root = new BorderPane();
        root.getStyleClass().add("cfg-editor");
        root.setTop(buildTitleStrip());
        root.setCenter(buildTabs());
        root.setBottom(buildFooter());
        root.setFocusTraversable(true);
        root.setOnKeyPressed(this::onShortcut);
        setPaneContent(root);
    }

    private Node buildTitleStrip() {
        titleLabel.getStyleClass().add("cfg-title");
        titleLabel.textProperty().bind(nameProperty);
        titleLabel.setCursor(javafx.scene.Cursor.TEXT);
        Tooltip.install(titleLabel, new Tooltip("Double-click to rename"));
        titleLabel.setOnMouseClicked(e -> { if (e.getClickCount() == 2) beginRenameInline(); });

        var typeLabel = new Label("Simulation configuration");
        typeLabel.getStyleClass().add("cfg-title-meta");

        dirtyPill.getStyleClass().add("cfg-title-dirty-pill");

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var statsBox = new HBox(16);
        statsBox.setAlignment(Pos.CENTER_LEFT);
        statsBox.getChildren().addAll(
            miniTitleStat("CHANNELS", Bindings.convert(store.totalChannels)),
            miniTitleStat("LARMOR", stringBinding(store.larmorHz, (DoubleFunction<String>) SimulationConfigEditorPane::formatFrequency)),
            miniTitleStat("dt", stringBinding(store.dtSeconds, (DoubleFunction<String>) SimulationConfigEditorPane::formatSeconds)),
            miniTitleStat("GRID",
                Bindings.createStringBinding(() -> store.nZ.get() + "\u00D7" + store.nR.get(), store.nZ, store.nR))
        );

        var strip = new HBox(10, titleLabel, typeLabel, dirtyPill, spacer, statsBox);
        strip.getStyleClass().add("cfg-title-strip");
        strip.setAlignment(Pos.CENTER_LEFT);
        return strip;
    }

    private Node miniTitleStat(String label, javafx.beans.value.ObservableValue<String> valueBinding) {
        var l = new Label(label);
        l.getStyleClass().add("cfg-title-stat-label");
        var v = new Label();
        v.getStyleClass().add("cfg-title-stat-value");
        v.textProperty().bind(valueBinding);
        var box = new VBox(0, l, v);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Node buildTabs() {
        tabs.getStyleClass().add("cfg-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        for (var t : List.of(overviewTab, referenceTab, tissueTab, geometryTab, fieldsTab, receiveCoilsTab)) t.setClosable(false);
        tabs.getTabs().addAll(overviewTab, referenceTab, tissueTab, geometryTab, fieldsTab, receiveCoilsTab);
        return tabs;
    }

    private Node buildFooter() {
        undoButton.getStyleClass().add("icon-button");
        undoButton.setTooltip(new Tooltip("Undo (\u2318Z)"));
        undoButton.setOnAction(e -> undo());
        redoButton.getStyleClass().add("icon-button");
        redoButton.setTooltip(new Tooltip("Redo (\u2318\u21E7Z)"));
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

    private void bindChrome() {
        refreshDirtyState();
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
        box.setFillWidth(true);
        var scroll = new ScrollPane(box);
        scroll.getStyleClass().add("cfg-tab-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    private void beginRenameInline() {
        var field = new TextField(nameProperty.get());
        field.getStyleClass().add("cfg-title-edit");
        field.setPrefColumnCount(22);
        Runnable commit = () -> {
            String newName = field.getText().trim();
            if (newName.isEmpty()) newName = nameProperty.get();
            if (!newName.equals(document.name())) renameDocument(newName);
            swap(field, titleLabel);
        };
        field.setOnAction(e -> commit.run());
        field.focusedProperty().addListener((obs, o, f) -> { if (!f) commit.run(); });
        field.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) { field.setText(nameProperty.get()); commit.run(); } });
        swap(titleLabel, field);
        field.requestFocus();
        field.selectAll();
    }

    private static void swap(Node before, Node after) {
        var parent = before.getParent();
        if (parent instanceof HBox box) {
            int idx = box.getChildren().indexOf(before);
            if (idx >= 0) box.getChildren().set(idx, after);
        }
    }

    private void renameDocument(String newName) {
        nameProperty.set(newName);
        setPaneTitle("Config: " + newName);
        document = document.withName(newName);
        var repo = paneContext.session().project.repository.get();
        repo.renameSimConfig(document.id(), newName);
        paneContext.session().project.explorer.refresh();
        notifyTitleChanged();
    }

    // ================================================================
    // Persistence + undo
    // ================================================================

    private void onConfigChanged(SimulationConfig oldC, SimulationConfig newC) {
        if (newC == null || newC.equals(oldC)) return;
        // Push the *previous* state onto undo when this is a user-driven change.
        if (!isBeingAppliedFromHistory) {
            if (oldC != null) {
                if (undoStack.size() >= MAX_UNDO) undoStack.removeLast();
                undoStack.push(oldC);
                redoStack.clear();
            }
        }

        document = document.withConfig(newC);
        var repo = paneContext.session().project.repository.get();
        repo.addSimConfig(document);
        paneContext.session().project.saveProjectQuietly();
        for (var simSession : paneContext.controller().allSimSessions()) {
            var activeDoc = simSession.activeConfigDoc.get();
            if (activeDoc != null && activeDoc.id().equals(document.id())) {
                simSession.updateConfigLive(newC);
            }
        }
        notifyTitleChanged();
        refreshDirtyState();
    }

    /** Set while restoring a historical snapshot, so onConfigChanged doesn't re-enqueue it. */
    private boolean isBeingAppliedFromHistory;

    private void undo() {
        if (undoStack.isEmpty()) return;
        var current = store.getConfig();
        var target = undoStack.pop();
        redoStack.push(current);
        isBeingAppliedFromHistory = true;
        try { store.setConfig(target); } finally { isBeingAppliedFromHistory = false; }
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        var current = store.getConfig();
        var target = redoStack.pop();
        undoStack.push(current);
        isBeingAppliedFromHistory = true;
        try { store.setConfig(target); } finally { isBeingAppliedFromHistory = false; }
    }

    private void revert() {
        if (store.getConfig().equals(savedConfig)) return;
        isBeingAppliedFromHistory = true;
        try {
            // Push current onto undo so Ctrl-Z restores it.
            var current = store.getConfig();
            if (undoStack.size() >= MAX_UNDO) undoStack.removeLast();
            undoStack.push(current);
            redoStack.clear();
            store.setConfig(savedConfig);
        } finally { isBeingAppliedFromHistory = false; }
    }

    public boolean isDirty() { return !store.getConfig().equals(savedConfig); }
    public String tabTitle() { return document.name() + (isDirty() ? " *" : ""); }
    public void setOnTitleChanged(Runnable callback) { this.onTitleChanged = callback; }
    private void notifyTitleChanged() { if (onTitleChanged != null) onTitleChanged.run(); }

    public void save() {
        var repo = paneContext.session().project.repository.get();
        repo.addSimConfig(document);
        paneContext.controller().saveProject();
        savedConfig = store.getConfig();
        notifyTitleChanged();
        refreshDirtyState();
        setPaneStatus("Saved " + document.name());
    }

    public void savePublic() { save(); }

    private void refreshDirtyState() {
        boolean dirty = isDirty();
        undoButton.setDisable(undoStack.isEmpty());
        redoButton.setDisable(redoStack.isEmpty());
        revertButton.setDisable(!dirty);
        saveButton.setDisable(!dirty);
        dirtyPill.setVisible(dirty);
        dirtyPill.setManaged(dirty);
        footerStatus.setText(dirty ? "Unsaved changes" : "Up to date");
        if (dirty) {
            if (!footerStatus.getStyleClass().contains("dirty")) footerStatus.getStyleClass().add("dirty");
        } else {
            footerStatus.getStyleClass().remove("dirty");
        }
    }

    // ================================================================
    // Tab: Overview
    // ================================================================

    private Node buildOverviewTab() {
        var box = new VBox(10);

        var metrics = new HBox();
        metrics.getStyleClass().add("cfg-metric-strip");
        metrics.setFillHeight(true);
        metrics.getChildren().addAll(
            bigMetric("REF B\u2080",    stringBinding(store.referenceB0Tesla, SimulationConfigEditorPane::formatTesla), "T", false),
            bigMetric("LARMOR",         stringBinding(store.larmorHz, SimulationConfigEditorPane::formatFrequencyShort),
                                        stringBinding(store.larmorHz, SimulationConfigEditorPane::frequencyUnit), false),
            bigMetric("TIME STEP",      stringBinding(store.dtSeconds, SimulationConfigEditorPane::formatDt),
                                        stringBinding(store.dtSeconds, SimulationConfigEditorPane::dtUnit), false),
            bigMetric("CHANNELS",       Bindings.convert(store.totalChannels), staticText(""), false),
            bigMetric("GRID",           Bindings.createStringBinding(() -> store.nZ.get() + "\u00D7" + store.nR.get(), store.nZ, store.nR), staticText(""), false),
            bigMetric("FIELDS",         Bindings.createStringBinding(() -> Integer.toString(store.fields.size()), store.fields), staticText(""), false),
            bigMetric("RX COILS",       Bindings.createStringBinding(() -> Integer.toString(store.receiveCoils.size()), store.receiveCoils), staticText(""), true)
        );
        box.getChildren().add(metrics);

        box.getChildren().add(sectionTitle("Health check"));
        box.getChildren().add(buildHealthChecksPane());

        box.getChildren().add(sectionTitle("Configuration at a glance"));
        box.getChildren().addAll(
            kvBound("Tissue", Bindings.createStringBinding(
                () -> String.format("T\u2081 = %s · T\u2082 = %s", formatMs(store.t1Ms.get()), formatMs(store.t2Ms.get())),
                store.t1Ms, store.t2Ms)),
            kvBound("Spatial FOV", Bindings.createStringBinding(
                () -> String.format("%.1f \u00D7 %.1f mm", store.fovZMm.get(), store.fovRMm.get()),
                store.fovZMm, store.fovRMm)),
            kvBound("Slice half-thickness", stringBinding(store.sliceHalfMm, v -> String.format("%.2f mm", v))),
            kvBound("Gyromagnetic ratio", stringBinding(store.gamma, v -> String.format("%.3f \u00D7 10\u2076 rad/s/T", v / 1e6))),
            kvBound("Reference period", stringBinding(store.larmorHz, v -> formatSeconds(1.0 / Math.max(v, 1e-12)))),
            kvBound("dt / T_ref", Bindings.createStringBinding(
                () -> String.format("%.4f cycles", store.dtSeconds.get() * Math.max(store.larmorHz.get(), 1e-12)),
                store.dtSeconds, store.larmorHz))
        );
        return box;
    }

    private Node bigMetric(String label, javafx.beans.value.ObservableValue<String> valueText,
                           javafx.beans.value.ObservableValue<String> unitText, boolean last) {
        var l = new Label(label);
        l.getStyleClass().add("cfg-metric-label");
        var v = new Label();
        v.getStyleClass().add("cfg-metric-value");
        v.textProperty().bind(valueText);
        var u = new Label();
        u.getStyleClass().add("cfg-metric-unit");
        u.textProperty().bind(unitText);
        var valueRow = new HBox(0, v, u);
        valueRow.setAlignment(Pos.BASELINE_LEFT);
        var box = new VBox(0, l, valueRow);
        box.getStyleClass().add("cfg-metric");
        if (last) box.getStyleClass().add("last");
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private Node bigMetric(String label, javafx.beans.value.ObservableValue<String> valueText, String unit, boolean last) {
        return bigMetric(label, valueText, new SimpleStringProperty(unit), last);
    }

    private Node buildHealthChecksPane() {
        var col = new VBox(2);
        ChangeListener<Object> rebuild = (obs, o, n) -> rebuildHealthChecks(col);
        // Trigger whenever anything on the store that affects checks changes.
        store.dtSeconds.addListener(rebuild);
        store.larmorHz.addListener(rebuild);
        store.referenceB0Tesla.addListener(rebuild);
        store.gamma.addListener(rebuild);
        store.nZ.addListener(rebuild);
        store.nR.addListener(rebuild);
        store.fields.addListener((javafx.collections.ListChangeListener<FieldDefinition>) ch -> rebuildHealthChecks(col));
        store.receiveCoils.addListener((javafx.collections.ListChangeListener<ReceiveCoil>) ch -> rebuildHealthChecks(col));
        rebuildHealthChecks(col);
        return col;
    }

    private void rebuildHealthChecks(VBox col) {
        col.getChildren().clear();
        double larmor = store.larmorHz.get();
        double dt = store.dtSeconds.get();
        double dtTimesLarmor = dt * larmor;

        if (dtTimesLarmor > 0.3) {
            col.getChildren().add(healthRow("danger", "Time step too large for Larmor cycle",
                String.format("dt/T_ref = %.2f cycles (need \u2264 0.05 for clean RF)", dtTimesLarmor)));
        } else if (dtTimesLarmor > 0.05) {
            col.getChildren().add(healthRow("warn", "Time step marginally resolved",
                String.format("dt samples Larmor period only %.1f\u00D7", 1.0 / dtTimesLarmor)));
        } else if (larmor > 0) {
            col.getChildren().add(healthRow("ok", "Time step resolves Larmor cycle",
                String.format("dt = 1/%.0f of T_ref = %s", 1.0 / dtTimesLarmor, formatSeconds(1.0 / larmor))));
        }

        double omegaSim = store.gamma.get() * store.referenceB0Tesla.get();
        for (var f : store.fields) {
            if (f.kind() == AmplitudeKind.QUADRATURE && f.carrierHz() > 0) {
                double dOmegaDt = Math.abs(2 * Math.PI * f.carrierHz() - omegaSim) * dt;
                if (dOmegaDt > 1.0) {
                    col.getChildren().add(healthRow("danger",
                        "Field '" + f.name() + "' off-resonance beyond Nyquist",
                        String.format("|\u0394\u03C9·dt| = %.2f — simulator will reject", dOmegaDt)));
                } else if (dOmegaDt > 0.1) {
                    col.getChildren().add(healthRow("warn",
                        "Field '" + f.name() + "' folded into Bloch\u2013Siegert",
                        String.format("|\u0394\u03C9·dt| = %.2f", dOmegaDt)));
                }
            }
        }

        if (store.fields.isEmpty()) {
            col.getChildren().add(healthRow("warn", "No field sources defined",
                "Add at least one static or driven field before simulating."));
        }

        if (store.receiveCoils.isEmpty()) {
            col.getChildren().add(healthRow("warn", "No receive coils defined",
                "Add at least one receive coil (Receive coils tab) to observe the signal."));
        }

        int cells = store.nZ.get() * store.nR.get();
        if (cells > 5000) {
            col.getChildren().add(healthRow("warn", "Large grid", cells + " cells — simulation may be slow."));
        }

        if (col.getChildren().isEmpty()) {
            col.getChildren().add(healthRow("ok", "All checks pass", ""));
        }
    }

    private Node healthRow(String level, String title, String detail) {
        var pill = new Label(level.toUpperCase(java.util.Locale.ROOT));
        pill.getStyleClass().addAll("status-pill", level);
        var t = new Label(title);
        t.getStyleClass().add("cfg-health-title");
        var d = new Label(detail);
        d.getStyleClass().add("cfg-health-detail");
        d.setWrapText(true);
        var text = new VBox(1, t);
        if (!detail.isEmpty()) text.getChildren().add(d);
        HBox.setHgrow(text, Priority.ALWAYS);
        var row = new HBox(8, pill, text);
        row.getStyleClass().add("cfg-health-row");
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

    // ================================================================
    // Tab: Reference frame
    // ================================================================

    private Node buildReferenceTab() {
        var box = new VBox(10);

        box.getChildren().addAll(
            sectionTitle("Rotating-frame reference"),
            sectionSubtitle("The simulator integrates Bloch equations in a frame rotating at \u03C9\u209B = \u03B3·B\u2080. "
                + "Choose a reference close to the working Larmor frequency so driven fields stay near resonance.")
        );

        var b0 = numberField(0, 30, 0.01).decimals(4);
        b0.valueProperty().bindBidirectional(store.referenceB0Tesla);
        box.getChildren().add(rowWithHint(
            "Reference B\u2080", b0, "tesla",
            stringBinding(store.larmorHz, v -> "Larmor \u03C9\u209B/2\u03C0 = " + formatFrequency(v))
        ));

        // Gamma — display in scientific (values are in the 10^8 range).
        var gamma = numberField(1e6, 1e9, 1e5).scientific();
        gamma.valueProperty().bindBidirectional(store.gamma);
        box.getChildren().add(rowWithHint(
            "Gyromagnetic ratio \u03B3", gamma, "rad/s/T",
            stringBinding(store.gamma, v -> String.format("\u03B3/2\u03C0 = %.3f MHz/T", v / (2 * Math.PI) / 1e6))
        ));

        box.getChildren().add(new Separator());
        box.getChildren().addAll(
            sectionTitle("Integration time step"),
            sectionSubtitle("Governs numerical accuracy. Fast fields with |\u0394\u03C9·dt| > 1 are folded into a "
                + "Bloch\u2013Siegert correction; when dt is too coarse the simulator rejects the configuration.")
        );

        // dt is stored in seconds but edited in microseconds.
        var dt = numberField(0.001, 10_000.0, 0.1).decimals(3);
        dt.setValue(store.dtSeconds.get() * 1e6);
        store.dtSeconds.addListener((obs, o, n) -> {
            double micros = n.doubleValue() * 1e6;
            if (Math.abs(dt.getValue() - micros) > 1e-12) dt.setValueQuiet(micros);
        });
        dt.valueProperty().addListener((obs, o, n) -> {
            double seconds = n.doubleValue() * 1e-6;
            if (Math.abs(store.dtSeconds.get() - seconds) > 1e-18) store.dtSeconds.set(seconds);
        });

        var dtStatus = new Label();
        dtStatus.setWrapText(true);
        dtStatus.textProperty().bind(Bindings.createStringBinding(
            () -> describeDt(store.dtSeconds.get(), store.larmorHz.get()).text,
            store.dtSeconds, store.larmorHz));
        dtStatus.getStyleClass().add("cfg-row-hint");
        applyDtStatusStyle(dtStatus);
        store.dtSeconds.addListener((obs, o, n) -> applyDtStatusStyle(dtStatus));
        store.larmorHz.addListener((obs, o, n) -> applyDtStatusStyle(dtStatus));

        var dtRow = rowControl("Simulation time step", dt, "\u03BCs");
        dtRow.getChildren().add(dtStatus);
        HBox.setHgrow(dtStatus, Priority.ALWAYS);
        box.getChildren().add(dtRow);

        box.getChildren().add(new Separator());
        box.getChildren().addAll(
            kvBound("Reference period",
                stringBinding(store.larmorHz, v -> formatSeconds(1.0 / Math.max(v, 1e-12)))),
            kvBound("dt / T_ref", Bindings.createStringBinding(
                () -> String.format("%.4f cycles", store.dtSeconds.get() * Math.max(store.larmorHz.get(), 1e-12)),
                store.dtSeconds, store.larmorHz)),
            kvBound("Nyquist limit",
                stringBinding(store.nyquistHz, SimulationConfigEditorPane::formatFrequency)),
            kvBound("Steps per 1 ms", stringBinding(store.dtSeconds,
                v -> String.format("%.0f", 1e-3 / Math.max(v, 1e-15))))
        );
        return box;
    }

    private record DtStatus(String level, String text) {}

    private static DtStatus describeDt(double dt, double larmor) {
        double cycles = dt * larmor;
        double nyq = dt > 0 ? 1.0 / (2 * dt) : Double.POSITIVE_INFINITY;
        if (cycles > 0.3) {
            return new DtStatus("danger", String.format("dt spans %.2f Larmor cycles — RF will alias. Lower dt below %s.",
                cycles, formatSeconds(0.05 / Math.max(larmor, 1e-12))));
        } else if (cycles > 0.05) {
            return new DtStatus("warn", String.format("Nyquist = %s · dt/T_ref = %.2f — coarse but usable.",
                formatFrequency(nyq), cycles));
        } else {
            return new DtStatus("hint", String.format("Nyquist = %s · resolves Larmor period to 1/%.0f",
                formatFrequency(nyq), 1.0 / Math.max(cycles, 1e-12)));
        }
    }

    private void applyDtStatusStyle(Label label) {
        var s = describeDt(store.dtSeconds.get(), store.larmorHz.get());
        label.getStyleClass().removeAll("cfg-row-hint", "cfg-row-warn", "cfg-row-danger");
        switch (s.level) {
            case "danger" -> label.getStyleClass().add("cfg-row-danger");
            case "warn"   -> label.getStyleClass().add("cfg-row-warn");
            default       -> label.getStyleClass().add("cfg-row-hint");
        }
    }

    // ================================================================
    // Tab: Tissue
    // ================================================================

    private Node buildTissueTab() {
        var box = new VBox(10);
        box.getChildren().addAll(
            sectionTitle("Relaxation times"),
            sectionSubtitle("T\u2081 drives Mz recovery; T\u2082 drives transverse dephasing.")
        );

        var t1 = numberField(0.1, 10_000, 10).decimals(3);
        t1.valueProperty().bindBidirectional(store.t1Ms);
        box.getChildren().add(rowWithHint(
            "T\u2081 · Longitudinal", t1, "ms",
            stringBinding(store.t1Ms, SimulationConfigEditorPane::tissueHintFor)));

        var t2 = numberField(0.1, 10_000, 10).decimals(3);
        t2.valueProperty().bindBidirectional(store.t2Ms);
        var t2Row = rowControl("T\u2082 · Transverse", t2, "ms");
        var t2Hint = new Label();
        t2Hint.setWrapText(true);
        t2Hint.textProperty().bind(Bindings.createStringBinding(() -> {
            if (store.t2Ms.get() > store.t1Ms.get())
                return "T\u2082 should not exceed T\u2081 in typical tissue — double-check the values.";
            return tissueHintFor(store.t2Ms.get());
        }, store.t1Ms, store.t2Ms));
        t2Hint.getStyleClass().add("cfg-row-hint");
        ChangeListener<Object> refreshT2Style = (obs, o, n) -> {
            t2Hint.getStyleClass().removeAll("cfg-row-hint", "cfg-row-warn");
            t2Hint.getStyleClass().add(store.t2Ms.get() > store.t1Ms.get() ? "cfg-row-warn" : "cfg-row-hint");
        };
        store.t1Ms.addListener(refreshT2Style);
        store.t2Ms.addListener(refreshT2Style);
        t2Row.getChildren().add(t2Hint);
        HBox.setHgrow(t2Hint, Priority.ALWAYS);
        box.getChildren().add(t2Row);

        // Live preview.
        var preview = new RelaxationPreview();
        preview.setParams(store.t1Ms.get(), store.t2Ms.get());
        ChangeListener<Number> repaint = (obs, o, n) -> preview.setParams(store.t1Ms.get(), store.t2Ms.get());
        store.t1Ms.addListener(repaint);
        store.t2Ms.addListener(repaint);
        preview.setHeight(140);
        preview.setWidth(520);
        box.getChildren().addAll(new Separator(), sectionTitle("Curve preview"), preview);
        return box;
    }

    private static String tissueHintFor(double tMs) {
        if (tMs < 20)   return "Very short — dense solid tissue / doped sample";
        if (tMs < 100)  return "Short — cortical bone, tendon, doped phantoms";
        if (tMs < 300)  return "Moderate — fat / white matter range";
        if (tMs < 1500) return "Soft-tissue range";
        return "Long — free water / CSF range";
    }

    // ================================================================
    // Tab: Geometry
    // ================================================================

    private Node buildGeometryTab() {
        var box = new VBox(10);
        box.getChildren().addAll(
            sectionTitle("Simulation volume"),
            sectionSubtitle("Samples field + magnetization on a 2-D r\u2013z grid. The slice band is the z-range "
                + "RF \"sees\" for selective excitation.")
        );

        var fovZ = numberField(0.1, 500, 1).decimals(2);
        fovZ.valueProperty().bindBidirectional(store.fovZMm);
        box.getChildren().add(rowWithHint("Axial FOV (z)", fovZ, "mm",
            Bindings.createStringBinding(() -> String.format("\u0394z = %.3f mm",
                store.fovZMm.get() / Math.max(1, store.nZ.get() - 1)), store.fovZMm, store.nZ)));

        var fovR = numberField(0.1, 500, 1).decimals(2);
        fovR.valueProperty().bindBidirectional(store.fovRMm);
        box.getChildren().add(rowWithHint("Radial FOV (r)", fovR, "mm",
            Bindings.createStringBinding(() -> String.format("\u0394r = %.3f mm",
                store.fovRMm.get() / Math.max(1, store.nR.get() - 1)), store.fovRMm, store.nR)));

        var nZ = numberField(2, 500, 1).decimals(0);
        nZ.valueProperty().bindBidirectional(castToDouble(store.nZ));
        box.getChildren().add(rowWithHint("Grid Z", nZ, "points",
            Bindings.createStringBinding(() -> (store.nZ.get() * store.nR.get()) + " cells total",
                store.nZ, store.nR)));

        var nR = numberField(2, 100, 1).decimals(0);
        nR.valueProperty().bindBidirectional(castToDouble(store.nR));
        box.getChildren().add(rowControl("Grid R", nR, "points"));

        var slice = numberField(0.01, 100, 0.5).decimals(3);
        slice.valueProperty().bindBidirectional(store.sliceHalfMm);
        box.getChildren().add(rowWithHint("Slice half-thickness", slice, "mm",
            stringBinding(store.sliceHalfMm, v -> String.format("Slice spans \u00B1%.2f mm (full = %.2f mm)", v, 2 * v))));

        // Large-grid warning
        var largeGridWarn = new Label();
        largeGridWarn.getStyleClass().add("cfg-row-warn");
        largeGridWarn.textProperty().bind(Bindings.createStringBinding(() -> {
            int cells = store.nZ.get() * store.nR.get();
            return cells > 5000 ? "\u26A0 Large grid: " + cells + " cells. Simulation may be slow." : "";
        }, store.nZ, store.nR));
        largeGridWarn.visibleProperty().bind(Bindings.createBooleanBinding(
            () -> store.nZ.get() * store.nR.get() > 5000, store.nZ, store.nR));
        largeGridWarn.managedProperty().bind(largeGridWarn.visibleProperty());
        box.getChildren().add(largeGridWarn);

        // Preview
        var preview = new GeometryPreview();
        ChangeListener<Number> repaint = (obs, o, n) ->
            preview.setGeometry(store.fovZMm.get(), store.fovRMm.get(), store.nZ.get(), store.nR.get(), store.sliceHalfMm.get());
        repaint.changed(null, null, null);
        store.fovZMm.addListener(repaint);
        store.fovRMm.addListener(repaint);
        store.nZ.addListener(repaint);
        store.nR.addListener(repaint);
        store.sliceHalfMm.addListener(repaint);
        preview.setWidth(520);
        preview.setHeight(220);

        box.getChildren().addAll(new Separator(), sectionTitle("r\u2013z grid"), preview);
        return box;
    }

    /** Adapter: expose an IntegerProperty as a DoubleProperty for binding with NumberField. */
    private static javafx.beans.property.DoubleProperty castToDouble(javafx.beans.property.IntegerProperty intProp) {
        var dbl = new javafx.beans.property.SimpleDoubleProperty(intProp.get());
        boolean[] syncing = {false};
        intProp.addListener((obs, o, n) -> {
            if (syncing[0]) return;
            syncing[0] = true;
            try { dbl.set(n.intValue()); } finally { syncing[0] = false; }
        });
        dbl.addListener((obs, o, n) -> {
            if (syncing[0]) return;
            syncing[0] = true;
            try { intProp.set((int) Math.round(n.doubleValue())); } finally { syncing[0] = false; }
        });
        return dbl;
    }

    // ================================================================
    // Tab: Fields (master-detail table)
    // ================================================================

    private Node buildFieldsTab() {
        var box = new VBox(8);
        box.getChildren().addAll(
            sectionTitle("Field sources"),
            sectionSubtitle("Each field couples an amplitude schedule to a spatial eigenfield. "
                + "STATIC = always-on DC · REAL = single-channel (gradients) · QUADRATURE = two-channel I/Q at a carrier.")
        );

        fieldsTable = buildFieldsTable();
        fieldsTable.setPrefHeight(180);
        fieldsTable.setMinHeight(120);
        fieldsTable.setMaxHeight(Double.MAX_VALUE);

        var addButton = new Button("+ Add field");
        addButton.getStyleClass().addAll("button", "primary");
        addButton.setOnAction(e -> addField());
        var removeButton = new Button("\u2212 Remove selected");
        removeButton.getStyleClass().add("button");
        removeButton.setOnAction(e -> {
            int idx = fieldsTable.getSelectionModel().getSelectedIndex();
            if (idx >= 0) removeField(idx);
        });
        removeButton.disableProperty().bind(fieldsTable.getSelectionModel().selectedItemProperty().isNull());

        var toolbar = new HBox(6, addButton, removeButton);
        toolbar.getStyleClass().add("table-action-bar");

        fieldsDetail = new VBox(6);
        fieldsDetail.setPadding(new javafx.geometry.Insets(8, 0, 0, 0));

        // Selection → rebuild the detail section. This only touches the detail,
        // not the rest of the tab, so typing never loses focus.
        fieldsTable.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            selectedField.set(n);
            rebuildFieldsDetail();
        });
        // External fields list changes (undo, etc.) — restore selection by index when possible.
        store.fields.addListener((javafx.collections.ListChangeListener<FieldDefinition>) ch -> {
            // TableView is already bound to the observable list via items.
            if (!store.fields.isEmpty() && fieldsTable.getSelectionModel().getSelectedItem() == null) {
                fieldsTable.getSelectionModel().selectFirst();
            }
            rebuildFieldsDetail();
        });

        if (!store.fields.isEmpty()) fieldsTable.getSelectionModel().selectFirst();
        else rebuildFieldsDetail();

        box.getChildren().addAll(toolbar, fieldsTable, new Separator(), fieldsDetail);
        return box;
    }

    private TableView<FieldDefinition> buildFieldsTable() {
        var table = new TableView<FieldDefinition>(store.fields);
        table.setPlaceholder(emptyState("No fields yet", "Add a static B\u2080, a gradient, or an RF coil."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        var nameCol = new TableColumn<FieldDefinition, String>("Name");
        nameCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().name()));
        var kindCol = new TableColumn<FieldDefinition, String>("Kind");
        kindCol.setCellValueFactory(cd -> new SimpleStringProperty(kindLabel(cd.getValue().kind())));
        var eigenCol = new TableColumn<FieldDefinition, String>("Eigenfield");
        eigenCol.setCellValueFactory(cd -> new SimpleStringProperty(eigenfieldNameFor(cd.getValue().eigenfieldId())));
        var minCol = new TableColumn<FieldDefinition, String>("Min amp");
        minCol.setCellValueFactory(cd -> new SimpleStringProperty(
            formatAmpWithUnits(cd.getValue().minAmplitude(), eigenfieldFor(cd.getValue()))));
        var maxCol = new TableColumn<FieldDefinition, String>("Max amp");
        maxCol.setCellValueFactory(cd -> new SimpleStringProperty(
            formatAmpWithUnits(cd.getValue().maxAmplitude(), eigenfieldFor(cd.getValue()))));
        var carrierCol = new TableColumn<FieldDefinition, String>("Carrier");
        carrierCol.setCellValueFactory(cd -> new SimpleStringProperty(
            cd.getValue().kind() == AmplitudeKind.QUADRATURE ? formatFrequency(cd.getValue().carrierHz()) : "\u2014"));
        var chanCol = new TableColumn<FieldDefinition, String>("Ch");
        chanCol.setCellValueFactory(cd -> new SimpleStringProperty(Integer.toString(cd.getValue().channelCount())));

        nameCol.setPrefWidth(140);
        kindCol.setPrefWidth(90);
        eigenCol.setPrefWidth(150);
        minCol.setPrefWidth(90);
        maxCol.setPrefWidth(90);
        carrierCol.setPrefWidth(100);
        chanCol.setPrefWidth(40);
        table.getColumns().addAll(nameCol, kindCol, eigenCol, minCol, maxCol, carrierCol, chanCol);
        return table;
    }

    private static String kindLabel(AmplitudeKind k) {
        return switch (k) {
            case STATIC -> "Static";
            case REAL -> "Real";
            case QUADRATURE -> "Quadrature";
        };
    }

    private void rebuildFieldsDetail() {
        if (fieldsDetail == null) return;
        fieldsDetail.getChildren().clear();

        var field = selectedField.get();
        int index = field == null ? -1 : store.fields.indexOf(field);
        if (field == null || index < 0) {
            var hint = new Label("Select a field to edit its properties.");
            hint.getStyleClass().add("cfg-empty-subtitle");
            fieldsDetail.getChildren().add(hint);
            return;
        }

        fieldsDetail.getChildren().add(sectionTitle("Edit field — " + field.name()));

        // Name
        var nameField = new TextField(field.name());
        nameField.setPrefColumnCount(18);
        Runnable commitName = () -> {
            int idx = store.fields.indexOf(field);
            if (idx < 0) return;
            var current = store.fields.get(idx);
            if (!current.name().equals(nameField.getText()))
                mutateField(idx, current.withName(nameField.getText()));
        };
        nameField.focusedProperty().addListener((obs, o, focused) -> { if (!focused) commitName.run(); });
        nameField.setOnAction(e -> commitName.run());
        fieldsDetail.getChildren().add(rowControl("Name", nameField, null));

        // Kind
        var kindControl = new SegmentedControl<AmplitudeKind>();
        kindControl.options(List.of(AmplitudeKind.STATIC, AmplitudeKind.REAL, AmplitudeKind.QUADRATURE),
            SimulationConfigEditorPane::kindLabel);
        kindControl.setValue(field.kind());
        kindControl.valueProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            int idx = store.fields.indexOf(field);
            if (idx < 0) return;
            var current = store.fields.get(idx);
            if (current.kind() != n) mutateField(idx, current.withKind(n));
        });
        var kindRow = rowControl("Kind", kindControl, null);
        var channelsLabel = new Label("Channels: " + field.channelCount());
        channelsLabel.getStyleClass().add("cfg-row-hint");
        kindRow.getChildren().add(channelsLabel);
        fieldsDetail.getChildren().add(kindRow);

        // Eigenfield selector
        fieldsDetail.getChildren().add(buildEigenfieldRow(field));

        // Amplitude bounds — the unit/peak label is driven by the eigenfield's
        // defaultMagnitude + units, so the user sees "= 15.4 mT" next to "0.0154".
        var eigen = eigenfieldFor(field);
        String ampUnits = eigen != null ? eigen.units() : "";
        double ampScale = eigen != null ? eigen.defaultMagnitude() : 1.0;
        double step = Math.max(Math.abs(field.maxAmplitude() - field.minAmplitude()) / 20.0, 1e-3);

        var minNum = numberField(-1e9, 1e9, step);
        minNum.setValue(field.minAmplitude());
        var minPeak = new Label();
        minPeak.getStyleClass().add("cfg-row-hint");
        minPeak.setText(peakLabel(field.minAmplitude(), ampScale, ampUnits));
        minNum.valueProperty().addListener((obs, o, n) -> {
            int idx = store.fields.indexOf(field);
            if (idx < 0) return;
            var current = store.fields.get(idx);
            if (current.minAmplitude() != n.doubleValue())
                mutateField(idx, current.withMinAmplitude(n.doubleValue()));
            minPeak.setText(peakLabel(n.doubleValue(), ampScale, ampUnits));
        });
        var minRow = rowControl("Min amplitude", minNum, ampUnits);
        minRow.getChildren().add(minPeak);
        fieldsDetail.getChildren().add(minRow);

        var maxNum = numberField(-1e9, 1e9, step);
        maxNum.setValue(field.maxAmplitude());
        var maxPeak = new Label();
        maxPeak.getStyleClass().add("cfg-row-hint");
        maxPeak.setText(peakLabel(field.maxAmplitude(), ampScale, ampUnits));
        maxNum.valueProperty().addListener((obs, o, n) -> {
            int idx = store.fields.indexOf(field);
            if (idx < 0) return;
            var current = store.fields.get(idx);
            if (current.maxAmplitude() != n.doubleValue())
                mutateField(idx, current.withMaxAmplitude(n.doubleValue()));
            maxPeak.setText(peakLabel(n.doubleValue(), ampScale, ampUnits));
        });
        var maxRow = rowControl("Max amplitude", maxNum, ampUnits);
        maxRow.getChildren().add(maxPeak);
        fieldsDetail.getChildren().add(maxRow);

        // Calibration hint: show the eigenfield's default magnitude × units.
        if (eigen != null && !ampUnits.isEmpty()) {
            var calib = new Label(String.format(
                "Amplitude 1 \u2192 %s peak (eigenfield default magnitude \u00D7 units)",
                formatSIValue(ampScale, ampUnits)));
            calib.getStyleClass().add("cfg-row-hint");
            fieldsDetail.getChildren().add(calib);
        }

        if (field.kind() == AmplitudeKind.QUADRATURE) {
            var carrier = numberField(0, 1e10, 1000);
            carrier.setValue(field.carrierHz());
            carrier.valueProperty().addListener((obs, o, n) -> {
                int idx = store.fields.indexOf(field);
                if (idx < 0) return;
                var current = store.fields.get(idx);
                if (current.carrierHz() != n.doubleValue())
                    mutateField(idx, current.withCarrierHz(n.doubleValue()));
            });
            var carrierStatus = new Label();
            carrierStatus.setWrapText(true);
            Runnable refreshCarrierHint = () -> {
                double omegaSim = store.gamma.get() * store.referenceB0Tesla.get();
                double dOmega = 2 * Math.PI * field.carrierHz() - omegaSim;
                double dOmegaDt = Math.abs(dOmega) * store.dtSeconds.get();
                double detuneHz = dOmega / (2 * Math.PI);
                String detuning = "\u0394\u03C9/2\u03C0 = " + (detuneHz >= 0 ? "+" : "\u2212")
                    + formatFrequency(Math.abs(detuneHz)) + " vs. reference";
                carrierStatus.getStyleClass().removeAll("cfg-row-hint", "cfg-row-warn", "cfg-row-danger");
                if (dOmegaDt > 1.0) {
                    carrierStatus.getStyleClass().add("cfg-row-danger");
                    carrierStatus.setText(detuning + " · |\u0394\u03C9·dt| = "
                        + String.format("%.2f — will be rejected", dOmegaDt));
                } else if (dOmegaDt > 0.1) {
                    carrierStatus.getStyleClass().add("cfg-row-warn");
                    carrierStatus.setText(detuning + " · |\u0394\u03C9·dt| = "
                        + String.format("%.2f (Bloch\u2013Siegert fast-weak)", dOmegaDt));
                } else {
                    carrierStatus.getStyleClass().add("cfg-row-hint");
                    carrierStatus.setText(detuning);
                }
            };
            refreshCarrierHint.run();
            var carrierRow = rowControl("Carrier", carrier, "Hz");
            carrierRow.getChildren().add(carrierStatus);
            HBox.setHgrow(carrierStatus, Priority.ALWAYS);
            fieldsDetail.getChildren().add(carrierRow);
        }
    }

    private Node buildEigenfieldRow(FieldDefinition field) {
        var combo = new ComboBox<String>();
        combo.setMinWidth(180);
        combo.setItems(eigenfieldNames);
        combo.setValue(eigenfieldNameFor(field.eigenfieldId()));
        combo.setOnAction(e -> {
            int idx = store.fields.indexOf(field);
            if (idx < 0) return;
            var selected = eigenfieldIdByName.get(combo.getValue());
            if (selected != null) mutateField(idx, store.fields.get(idx).withEigenfieldId(selected));
        });

        var openButton = new Button("Open");
        openButton.getStyleClass().addAll("button", "ghost");
        openButton.setDisable(field.eigenfieldId() == null);
        openButton.setOnAction(e -> {
            if (field.eigenfieldId() != null)
                paneContext.session().project.openNode(field.eigenfieldId());
        });

        var newButton = new Button("New…");
        newButton.getStyleClass().addAll("button", "ghost");
        newButton.setOnAction(e -> {
            var stage = topStage();
            ax.xz.mri.ui.wizard.NewEigenfieldWizard.show(stage, paneContext.session().project)
                .ifPresent(eigen -> {
                    int idx = store.fields.indexOf(field);
                    if (idx >= 0) mutateField(idx, store.fields.get(idx).withEigenfieldId(eigen.id()));
                });
        });

        var comboRow = new HBox(6, combo, openButton, newButton);
        comboRow.setAlignment(Pos.CENTER_LEFT);
        return rowControl("Eigenfield", comboRow, null);
    }

    /** Look up the eigenfield document for a given field, or null. */
    private ax.xz.mri.project.EigenfieldDocument eigenfieldFor(FieldDefinition field) {
        if (field == null || field.eigenfieldId() == null) return null;
        var repo = paneContext.session().project.repository.get();
        return repo.node(field.eigenfieldId()) instanceof ax.xz.mri.project.EigenfieldDocument ef ? ef : null;
    }

    /** "= 15.4 mT" — amplitude × eigenfield.defaultMagnitude, with auto SI prefix. */
    private static String peakLabel(double amplitude, double scale, String units) {
        if (units.isEmpty()) return "= " + formatSIValue(amplitude * scale, "");
        return "= " + formatSIValue(amplitude * scale, units);
    }

    /** Format a physical value with an auto SI prefix and appended units. */
    private static String formatSIValue(double value, String units) {
        double abs = Math.abs(value);
        if (abs == 0) return units.isEmpty() ? "0" : "0 " + units;
        String prefix;
        double scale;
        if      (abs >= 1e9)  { prefix = "G";  scale = 1e9; }
        else if (abs >= 1e6)  { prefix = "M";  scale = 1e6; }
        else if (abs >= 1e3)  { prefix = "k";  scale = 1e3; }
        else if (abs >= 1)    { prefix = "";   scale = 1;   }
        else if (abs >= 1e-3) { prefix = "m";  scale = 1e-3; }
        else if (abs >= 1e-6) { prefix = "\u03BC"; scale = 1e-6; }
        else if (abs >= 1e-9) { prefix = "n";  scale = 1e-9; }
        else                  { prefix = "p";  scale = 1e-12; }
        double display = value / scale;
        String num;
        if (Math.abs(display) >= 100) num = String.format("%.1f", display);
        else if (Math.abs(display) >= 10) num = String.format("%.2f", display);
        else num = String.format("%.3f", display);
        // Trim trailing zeros after decimal
        if (num.contains(".")) num = num.replaceAll("0+$", "").replaceAll("\\.$", "");
        return units.isEmpty() ? num : num + " " + prefix + units;
    }

    /** Table-cell amplitude formatter — peak physical value with SI prefix, or the raw number if no eigenfield. */
    private static String formatAmpWithUnits(double amplitude, ax.xz.mri.project.EigenfieldDocument ef) {
        if (ef == null) return formatAmp(amplitude);
        return formatSIValue(amplitude * ef.defaultMagnitude(), ef.units());
    }

    private String eigenfieldNameFor(ProjectNodeId id) {
        if (id == null) return "\u2014";
        var repo = paneContext.session().project.repository.get();
        if (repo.node(id) instanceof EigenfieldDocument ef) return ef.name();
        return "(missing)";
    }

    private void refreshEigenfields() {
        var repo = paneContext.session().project.repository.get();
        eigenfieldNames.clear();
        eigenfieldIdByName.clear();
        for (var efId : repo.eigenfieldIds()) {
            if (repo.node(efId) instanceof EigenfieldDocument ef) {
                eigenfieldNames.add(ef.name());
                eigenfieldIdByName.put(ef.name(), ef.id());
            }
        }
        // Table column renders via cellValueFactory reading the name, so nudge a refresh.
        if (fieldsTable != null) fieldsTable.refresh();
        rebuildFieldsDetail();
    }

    // ================================================================
    // Fields mutation helpers
    // ================================================================

    /** Replace element at {@code index} via {@link ConfigStore}. */
    private void mutateField(int index, FieldDefinition updated) {
        if (index < 0 || index >= store.fields.size()) return;
        if (store.fields.get(index).equals(updated)) return;
        store.fields.set(index, updated);
        // Re-select and refresh the detail row so it reflects the new identity.
        fieldsTable.getSelectionModel().select(index);
        selectedField.set(updated);
        rebuildFieldsDetail();
    }

    private void removeField(int index) {
        if (index < 0 || index >= store.fields.size()) return;
        store.fields.remove(index);
        if (!store.fields.isEmpty()) {
            int next = Math.min(index, store.fields.size() - 1);
            fieldsTable.getSelectionModel().select(next);
        }
    }

    private void addField() {
        var repo = paneContext.session().project.repository.get();
        var efIds = repo.eigenfieldIds();
        if (efIds.isEmpty()) {
            var stage = topStage();
            ax.xz.mri.ui.wizard.NewEigenfieldWizard.show(stage, paneContext.session().project)
                .ifPresent(eigen -> appendField(eigen.id()));
            return;
        }
        appendField(efIds.get(0));
    }

    private void appendField(ProjectNodeId eigenfieldId) {
        store.fields.add(new FieldDefinition("New Field", eigenfieldId, AmplitudeKind.REAL, 0, -1, 1));
        paneContext.session().project.explorer.refresh();
        fieldsTable.getSelectionModel().select(store.fields.size() - 1);
    }

    // ================================================================
    // Tab: Receive coils (master-detail table)
    // ================================================================

    private Node buildReceiveCoilsTab() {
        var box = new VBox(8);
        box.getChildren().addAll(
            sectionTitle("Receive coils"),
            sectionSubtitle("Each receive coil observes the transverse magnetisation through its own eigenfield "
                + "(spatial sensitivity). The complex voltage the coil reports is "
                + "gain \u00B7 e^(i\u00B7phase) \u00B7 \u222B (E\u2093 - i\u00B7E\u1d67) \u00B7 (M\u2093 + i\u00B7M\u1d67) dV.")
        );

        receiveCoilsTable = buildReceiveCoilsTable();
        receiveCoilsTable.setPrefHeight(160);
        receiveCoilsTable.setMinHeight(100);

        var addButton = new Button("+ Add receive coil");
        addButton.getStyleClass().addAll("button", "primary");
        addButton.setOnAction(e -> addReceiveCoil());
        var removeButton = new Button("\u2212 Remove selected");
        removeButton.getStyleClass().add("button");
        removeButton.setOnAction(e -> {
            int idx = receiveCoilsTable.getSelectionModel().getSelectedIndex();
            if (idx >= 0) removeReceiveCoil(idx);
        });
        removeButton.disableProperty().bind(receiveCoilsTable.getSelectionModel().selectedItemProperty().isNull());

        var toolbar = new HBox(6, addButton, removeButton);
        toolbar.getStyleClass().add("table-action-bar");

        receiveCoilsDetail = new VBox(6);
        receiveCoilsDetail.setPadding(new javafx.geometry.Insets(8, 0, 0, 0));

        receiveCoilsTable.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            selectedReceiveCoil.set(n);
            rebuildReceiveCoilDetail();
        });
        store.receiveCoils.addListener((javafx.collections.ListChangeListener<ReceiveCoil>) ch -> {
            if (!store.receiveCoils.isEmpty() && receiveCoilsTable.getSelectionModel().getSelectedItem() == null) {
                receiveCoilsTable.getSelectionModel().selectFirst();
            }
            rebuildReceiveCoilDetail();
        });

        if (!store.receiveCoils.isEmpty()) receiveCoilsTable.getSelectionModel().selectFirst();
        else rebuildReceiveCoilDetail();

        box.getChildren().addAll(toolbar, receiveCoilsTable, new Separator(), receiveCoilsDetail);
        return box;
    }

    private TableView<ReceiveCoil> buildReceiveCoilsTable() {
        var table = new TableView<ReceiveCoil>(store.receiveCoils);
        table.setPlaceholder(emptyState("No receive coils yet",
            "Add one (uniform isotropic, reciprocal Helmholtz, surface loop) to observe the transverse magnetisation."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        var nameCol = new TableColumn<ReceiveCoil, String>("Name");
        nameCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().name()));
        var eigenCol = new TableColumn<ReceiveCoil, String>("Eigenfield");
        eigenCol.setCellValueFactory(cd -> new SimpleStringProperty(eigenfieldNameFor(cd.getValue().eigenfieldId())));
        var gainCol = new TableColumn<ReceiveCoil, String>("Gain");
        gainCol.setCellValueFactory(cd -> new SimpleStringProperty(String.format("%.3g", cd.getValue().gain())));
        var phaseCol = new TableColumn<ReceiveCoil, String>("Phase");
        phaseCol.setCellValueFactory(cd -> new SimpleStringProperty(String.format("%.1f\u00B0", cd.getValue().phaseDeg())));
        var primaryCol = new TableColumn<ReceiveCoil, String>("Primary");
        primaryCol.setCellValueFactory(cd -> new SimpleStringProperty(
            store.receiveCoils.indexOf(cd.getValue()) == 0 ? "\u2713" : ""));

        nameCol.setPrefWidth(150);
        eigenCol.setPrefWidth(180);
        gainCol.setPrefWidth(80);
        phaseCol.setPrefWidth(80);
        primaryCol.setPrefWidth(70);
        table.getColumns().addAll(nameCol, eigenCol, gainCol, phaseCol, primaryCol);
        return table;
    }

    private void rebuildReceiveCoilDetail() {
        if (receiveCoilsDetail == null) return;
        receiveCoilsDetail.getChildren().clear();

        var coil = selectedReceiveCoil.get();
        int index = coil == null ? -1 : store.receiveCoils.indexOf(coil);
        if (coil == null || index < 0) {
            var hint = new Label("Select a receive coil to edit, or add one.");
            hint.getStyleClass().add("cfg-empty-subtitle");
            receiveCoilsDetail.getChildren().add(hint);
            return;
        }

        receiveCoilsDetail.getChildren().add(sectionTitle("Edit receive coil \u2014 " + coil.name()));

        var nameField = new TextField(coil.name());
        nameField.setPrefColumnCount(18);
        Runnable commitName = () -> {
            int idx = store.receiveCoils.indexOf(coil);
            if (idx < 0) return;
            var current = store.receiveCoils.get(idx);
            if (!current.name().equals(nameField.getText().trim()) && !nameField.getText().trim().isBlank())
                mutateReceiveCoil(idx, current.withName(nameField.getText().trim()));
        };
        nameField.focusedProperty().addListener((obs, o, focused) -> { if (!focused) commitName.run(); });
        nameField.setOnAction(e -> commitName.run());
        receiveCoilsDetail.getChildren().add(rowControl("Name", nameField, null));

        receiveCoilsDetail.getChildren().add(buildReceiveCoilEigenfieldRow(coil));

        var gainNum = numberField(-1e9, 1e9, 0.1);
        gainNum.setValue(coil.gain());
        gainNum.valueProperty().addListener((obs, o, n) -> {
            int idx = store.receiveCoils.indexOf(coil);
            if (idx < 0 || n == null) return;
            var current = store.receiveCoils.get(idx);
            if (current.gain() != n.doubleValue())
                mutateReceiveCoil(idx, current.withGain(n.doubleValue()));
        });
        receiveCoilsDetail.getChildren().add(rowControl("Gain", gainNum, null));

        var phaseNum = numberField(-360, 360, 15);
        phaseNum.setValue(coil.phaseDeg());
        phaseNum.valueProperty().addListener((obs, o, n) -> {
            int idx = store.receiveCoils.indexOf(coil);
            if (idx < 0 || n == null) return;
            var current = store.receiveCoils.get(idx);
            if (current.phaseDeg() != n.doubleValue())
                mutateReceiveCoil(idx, current.withPhaseDeg(n.doubleValue()));
        });
        receiveCoilsDetail.getChildren().add(rowControl("Phase", phaseNum, "\u00B0"));

        if (index == 0) {
            var note = new Label("This is the primary coil \u2014 its trace is what the magnitude pane shows.");
            note.getStyleClass().add("cfg-row-hint");
            receiveCoilsDetail.getChildren().add(note);
        } else {
            var makePrimary = new Button("Make primary");
            makePrimary.getStyleClass().addAll("button", "ghost");
            makePrimary.setOnAction(e -> {
                var list = new java.util.ArrayList<>(store.receiveCoils);
                list.add(0, list.remove(index));
                store.receiveCoils.setAll(list);
                receiveCoilsTable.getSelectionModel().select(0);
            });
            receiveCoilsDetail.getChildren().add(makePrimary);
        }
    }

    private Node buildReceiveCoilEigenfieldRow(ReceiveCoil coil) {
        var combo = new ComboBox<String>();
        combo.setMinWidth(180);
        combo.setItems(eigenfieldNames);
        combo.setValue(eigenfieldNameFor(coil.eigenfieldId()));
        combo.setOnAction(e -> {
            int idx = store.receiveCoils.indexOf(coil);
            if (idx < 0) return;
            var selected = eigenfieldIdByName.get(combo.getValue());
            if (selected != null) mutateReceiveCoil(idx, store.receiveCoils.get(idx).withEigenfieldId(selected));
        });

        var openButton = new Button("Open");
        openButton.getStyleClass().addAll("button", "ghost");
        openButton.setDisable(coil.eigenfieldId() == null);
        openButton.setOnAction(e -> {
            if (coil.eigenfieldId() != null)
                paneContext.session().project.openNode(coil.eigenfieldId());
        });

        var newButton = new Button("New\u2026");
        newButton.getStyleClass().addAll("button", "ghost");
        newButton.setOnAction(e -> {
            var stage = topStage();
            ax.xz.mri.ui.wizard.NewEigenfieldWizard.show(stage, paneContext.session().project)
                .ifPresent(eigen -> {
                    int idx = store.receiveCoils.indexOf(coil);
                    if (idx >= 0) mutateReceiveCoil(idx, store.receiveCoils.get(idx).withEigenfieldId(eigen.id()));
                });
        });

        var comboRow = new HBox(6, combo, openButton, newButton);
        comboRow.setAlignment(Pos.CENTER_LEFT);
        return rowControl("Eigenfield", comboRow, null);
    }

    private void mutateReceiveCoil(int index, ReceiveCoil updated) {
        if (index < 0 || index >= store.receiveCoils.size()) return;
        if (store.receiveCoils.get(index).equals(updated)) return;
        store.receiveCoils.set(index, updated);
        receiveCoilsTable.getSelectionModel().select(index);
        selectedReceiveCoil.set(updated);
        rebuildReceiveCoilDetail();
    }

    private void removeReceiveCoil(int index) {
        if (index < 0 || index >= store.receiveCoils.size()) return;
        store.receiveCoils.remove(index);
        if (!store.receiveCoils.isEmpty()) {
            int next = Math.min(index, store.receiveCoils.size() - 1);
            receiveCoilsTable.getSelectionModel().select(next);
        }
    }

    /**
     * Seed a new receive coil from the default starter, creating its backing
     * eigenfield in the project repository if it doesn't already exist.
     */
    private void addReceiveCoil() {
        var starter = ReceiveCoilStarterLibrary.defaultStarter();
        var eigenStarter = EigenfieldStarterLibrary.byId(starter.eigenfieldStarterId()).orElseThrow();
        var repo = paneContext.session().project.repository.get();
        var eigen = ObjectFactory.findOrCreateEigenfield(repo,
            starter.name(), eigenStarter.description(), eigenStarter.source(),
            eigenStarter.units(), eigenStarter.defaultMagnitude());
        store.receiveCoils.add(new ReceiveCoil(
            starter.name() + (store.receiveCoils.isEmpty() ? "" : " " + (store.receiveCoils.size() + 1)),
            eigen.id(), starter.gain(), starter.phaseDeg()));
        paneContext.session().project.explorer.refresh();
        receiveCoilsTable.getSelectionModel().select(store.receiveCoils.size() - 1);
    }

    // ================================================================
    // Common helpers
    // ================================================================

    private Label sectionTitle(String text) {
        var l = new Label(text);
        l.getStyleClass().add("cfg-section-title");
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }

    private Label sectionSubtitle(String text) {
        var l = new Label(text);
        l.getStyleClass().add("cfg-section-subtitle");
        l.setWrapText(true);
        l.setMaxWidth(760);
        return l;
    }

    private HBox rowControl(String label, Node control, String unit) {
        var l = new Label(label);
        l.getStyleClass().add("cfg-row-label");
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

    /** Row with a control and an auto-updating hint/warn/danger text on the right. */
    private HBox rowWithHint(String label, Node control, String unit,
                             javafx.beans.value.ObservableValue<String> hint) {
        var row = rowControl(label, control, unit);
        var hintLabel = new Label();
        hintLabel.getStyleClass().add("cfg-row-hint");
        hintLabel.setWrapText(true);
        hintLabel.textProperty().bind(hint);
        HBox.setHgrow(hintLabel, Priority.ALWAYS);
        row.getChildren().add(hintLabel);
        return row;
    }

    private Node emptyState(String title, String subtitle) {
        var t = new Label(title);
        t.getStyleClass().add("cfg-empty-title");
        var s = new Label(subtitle);
        s.getStyleClass().add("cfg-empty-subtitle");
        s.setWrapText(true);
        var box = new VBox(4, t, s);
        box.getStyleClass().add("cfg-empty-state");
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private static NumberField numberField(double min, double max, double step) {
        var nf = new NumberField().range(min, max).step(step);
        nf.prefColumnCount(7);
        return nf;
    }

    private Stage topStage() {
        return javafx.stage.Window.getWindows().stream()
            .filter(w -> w instanceof Stage && w.isShowing())
            .map(w -> (Stage) w).findFirst().orElse(null);
    }

    private static StringBinding stringBinding(javafx.beans.value.ObservableValue<? extends Number> source,
                                               DoubleFunction<String> fn) {
        return Bindings.createStringBinding(() -> fn.apply(source.getValue().doubleValue()), source);
    }

    private static javafx.beans.value.ObservableValue<String> staticText(String s) {
        return new SimpleStringProperty(s);
    }

    // ------------ Formatting ------------

    private static String formatTesla(double t) {
        return Math.abs(t) < 0.1 ? String.format("%.4f", t) : String.format("%.3f", t);
    }

    private static String formatAmp(double a) {
        if (a == 0) return "0";
        double abs = Math.abs(a);
        if (abs >= 100) return String.format("%.2f", a);
        if (abs >= 1) return String.format("%.3f", a);
        if (abs >= 1e-3) return String.format("%.4f", a);
        return String.format("%.3g", a);
    }

    private static String formatMs(double ms) {
        if (ms < 1) return String.format("%.3f ms", ms);
        if (ms < 100) return String.format("%.1f ms", ms);
        return String.format("%.0f ms", ms);
    }

    private static String formatFrequency(double hz) {
        double abs = Math.abs(hz);
        if (abs >= 1e9) return String.format("%.2f GHz", hz / 1e9);
        if (abs >= 1e6) return String.format("%.2f MHz", hz / 1e6);
        if (abs >= 1e3) return String.format("%.2f kHz", hz / 1e3);
        return String.format("%.1f Hz", hz);
    }

    private static String formatFrequencyShort(double hz) {
        double abs = Math.abs(hz);
        if (abs >= 1e9) return String.format("%.2f", hz / 1e9);
        if (abs >= 1e6) return String.format("%.2f", hz / 1e6);
        if (abs >= 1e3) return String.format("%.2f", hz / 1e3);
        return String.format("%.1f", hz);
    }

    private static String frequencyUnit(double hz) {
        double abs = Math.abs(hz);
        if (abs >= 1e9) return "GHz";
        if (abs >= 1e6) return "MHz";
        if (abs >= 1e3) return "kHz";
        return "Hz";
    }

    private static String formatDt(double seconds) {
        if (seconds < 1e-6) return String.format("%.2f", seconds * 1e9);
        if (seconds < 1e-3) return String.format("%.3f", seconds * 1e6);
        if (seconds < 1)    return String.format("%.3f", seconds * 1e3);
        return String.format("%.3f", seconds);
    }

    private static String dtUnit(double seconds) {
        if (seconds < 1e-6) return "ns";
        if (seconds < 1e-3) return "\u03BCs";
        if (seconds < 1)    return "ms";
        return "s";
    }

    private static String formatSeconds(double s) {
        if (s < 1e-9) return String.format("%.2f ps", s * 1e12);
        if (s < 1e-6) return String.format("%.2f ns", s * 1e9);
        if (s < 1e-3) return String.format("%.3f \u03BCs", s * 1e6);
        if (s < 1)    return String.format("%.3f ms", s * 1e3);
        return String.format("%.3f s", s);
    }
}
