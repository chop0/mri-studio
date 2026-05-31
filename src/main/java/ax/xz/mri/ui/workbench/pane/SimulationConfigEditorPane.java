package ax.xz.mri.ui.workbench.pane;

import module ax.xz.mri;
import module javafx.base;
import module javafx.controls;
import module javafx.graphics;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.project.SubstanceDocument;
import ax.xz.mri.state.Mutation;
import ax.xz.mri.state.Scope;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import ax.xz.mri.ui.workbench.pane.config.ConfigStore;
import ax.xz.mri.ui.workbench.pane.config.NumberField;
import ax.xz.mri.ui.workbench.pane.schematic.CircuitEditSession;
import ax.xz.mri.ui.workbench.pane.schematic.SchematicPane;
import ax.xz.mri.util.SiFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.time.Instant;

/**
 * Tabbed editor for a {@link SimulationConfigDocument}.
 *
 * <p>Tabs:
 * <ul>
 *   <li><b>Overview</b> — top-line metrics + a glanceable summary of every
 *       knob, plus a read-only substance panel pulled from the project.</li>
 *   <li><b>Reference</b> — rotating-frame reference B₀ and the integration
 *       time step.</li>
 *   <li><b>Schematic</b> — the {@link SchematicPane} editing the associated
 *       circuit.</li>
 * </ul>
 *
 * <p>Spatial layout (extent + resolution) is a property of the substance the
 * circuit references — it's edited in the substance pane, not here. Pulling
 * the FOV onto the simulation config created two sources of truth for the
 * same physical fact; Part 12 of the rebuild merged them.
 *
 * <p>{@link ConfigStore} is the single source of truth; every control binds
 * bidirectionally. The schematic tab's {@link CircuitEditSession} shares the
 * same repository via the pane context, so mutations land back on the
 * {@link CircuitDocument} immediately.
 */
public final class SimulationConfigEditorPane extends WorkbenchPane {

    private final SimulationConfigDocument document;
    private final ConfigStore store;

    private final SimpleStringProperty nameProperty = new SimpleStringProperty();
    private final Label titleLabel = new Label();
    private final Label footerStatus = new Label();

    private final TabPane tabs = new TabPane();
    private final Tab overviewTab = new Tab("Overview");
    private final Tab referenceTab = new Tab("Reference");
    private final Tab schematicTab = new Tab("Schematic");

    private CircuitEditSession circuitSession;
    private SchematicPane schematicPane;
    private Runnable onTitleChanged;

    public SimulationConfigEditorPane(PaneContext paneContext, SimulationConfigDocument document) {
        super(paneContext);
        this.document = document;
        this.nameProperty.set(document.name());
        setPaneTitle("Config: " + document.name());
        this.store = new ConfigStore(document.config());

        buildShell();

        overviewTab.setContent(scrollWrap(buildOverviewTab()));
        referenceTab.setContent(scrollWrap(buildReferenceTab()));
        schematicTab.setContent(buildSchematicTab());

        store.config.addListener((obs, oldC, newC) -> onConfigChanged(oldC, newC));
    }

    public SimulationConfigDocument document() { return document; }

    public void setOnTitleChanged(Runnable listener) { this.onTitleChanged = listener; }

    public void undo() { paneContext.session().state.undoIn(
        paneContext.session().state.withinScope(Scope.indexed(Scope.root(), "simulations", document.id()))); }

    public void redo() { paneContext.session().state.redoIn(
        paneContext.session().state.withinScope(Scope.indexed(Scope.root(), "simulations", document.id()))); }

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
    }

    private Node buildTitleStrip() {
        titleLabel.getStyleClass().add("cfg-title");
        titleLabel.textProperty().bind(nameProperty);
        titleLabel.setCursor(javafx.scene.Cursor.TEXT);
        Tooltip.install(titleLabel, new Tooltip("Double-click to rename"));
        titleLabel.setOnMouseClicked(e -> { if (e.getClickCount() == 2) beginRename(); });

        var typeLabel = new Label("Simulation configuration");
        typeLabel.getStyleClass().add("cfg-title-meta");

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var stats = new HBox(16);
        stats.setAlignment(Pos.CENTER_LEFT);
        stats.getChildren().addAll(
            stat("LARMOR", fmt(store.larmorHz, SimulationConfigEditorPane::formatFrequencyShort)),
            stat("dt",     fmt(store.dtSeconds, SimulationConfigEditorPane::formatSeconds))
        );

        var strip = new HBox(10, titleLabel, typeLabel, spacer, stats);
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
        // Pin a generous fixed pref-width so this stat tile doesn't re-measure
        // when its value's character count changes. Without this, every nX/nY/nZ
        // edit reflows the title strip → toolbar → schematic canvas.
        l.setPrefWidth(72);
        l.setMinWidth(72);
        v.setPrefWidth(72);
        v.setMinWidth(72);
        var box = new VBox(0, l, v);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMinWidth(72);
        box.setPrefWidth(72);
        return box;
    }

    private Node buildTabs() {
        tabs.getStyleClass().add("cfg-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        for (var t : List.of(overviewTab, referenceTab, schematicTab)) t.setClosable(false);
        tabs.getTabs().addAll(overviewTab, referenceTab, schematicTab);
        return tabs;
    }

    private Node buildFooter() {
        footerStatus.getStyleClass().add("cfg-footer-status");

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var footer = new HBox(10, footerStatus, spacer);
        footer.getStyleClass().add("cfg-footer");
        return footer;
    }

    private void onShortcut(javafx.scene.input.KeyEvent event) {
        if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN).match(event)) {
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
            bigMetric("REF B₀", fmt(store.referenceB0Tesla, SimulationConfigEditorPane::formatTesla), "T", false),
            bigMetric("LARMOR", fmt(store.larmorHz, SimulationConfigEditorPane::formatFrequencyShort),
                fmt(store.larmorHz, SimulationConfigEditorPane::frequencyUnit), false),
            bigMetric("TIME STEP", fmt(store.dtSeconds, SimulationConfigEditorPane::formatDt),
                fmt(store.dtSeconds, SimulationConfigEditorPane::dtUnit), true)
        );
        box.getChildren().add(metrics);

        box.getChildren().add(sectionTitle("Configuration at a glance"));
        box.getChildren().addAll(
            kvBound("Reference period", fmt(store.larmorHz,
                v -> Double.isNaN(v) || v <= 0 ? "—" : formatSeconds(1.0 / v))),
            kvBound("Circuit", Bindings.createStringBinding(
                () -> {
                    var id = store.circuitId.get();
                    if (id == null) return "(none)";
                    var repo = paneContext.session().state.current();
                    var doc = repo.circuit(id);
                    return doc == null ? "(missing)" : doc.name();
                },
                store.circuitId))
        );

        box.getChildren().add(new Separator());
        box.getChildren().add(buildSubstanceSection());
        return box;
    }

    /**
     * Read-only "Substance" section listing every substance the circuit's
     * Substance blocks reference. Tissue physics (T₁, T₂, γ) live on the
     * substance, not the simulation config — this section reads them from
     * the resolved {@link ax.xz.mri.project.SubstanceDocument}s. When the
     * circuit has no substance blocks, the section reports "No substance"
     * explicitly — no proton fallback.
     */
    private Node buildSubstanceSection() {
        var box = new VBox(8);
        var headerRow = new HBox(8);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        var header = new Label("Substance");
        header.getStyleClass().add("cfg-section-title");
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        headerRow.getChildren().addAll(header, spacer);
        box.getChildren().add(headerRow);

        var content = new VBox(4);
        Runnable refresh = () -> {
            content.getChildren().clear();
            var docs = resolveSubstances();
            if (docs.isEmpty()) {
                content.getChildren().add(kvStatic("Substance", "(none — add a Substance block to the circuit)"));
                return;
            }
            for (var doc : docs) {
                var sub = doc.substance();
                content.getChildren().add(kvStatic("Name", doc.name()));
                switch (sub) {
                    case ContinuousMagnetisation cm -> content.getChildren().addAll(
                        kvStatic("Kind", "Continuous magnetisation"),
                        kvStatic("T₁", String.format("%.3f s", cm.t1Seconds())),
                        kvStatic("T₂", String.format("%.3f s", cm.t2Seconds())),
                        kvStatic("γ", String.format("%.3f × 10⁶ rad/s/T",
                            cm.gammaRadPerSecPerTesla() / 1e6)),
                        kvStatic("FOV", SiFormat.fovExtents(
                            cm.halfExtentXMetres(), cm.halfExtentYMetres(), cm.halfExtentZMetres())),
                        kvStatic("Grid", cm.nX() + " × " + cm.nY() + " × " + cm.nZ())
                    );
                    case NvEnsemble nv -> {
                        var h = nv.halfExtent();
                        content.getChildren().addAll(
                            kvStatic("Kind", "NV ensemble"),
                            kvStatic("Centres", String.valueOf(nv.centres().size())),
                            kvStatic("γ (electron)", String.format("%.3f × 10⁹ rad/s/T",
                                nv.physics().gammaRadPerSecPerTesla() / 1e9)),
                            kvStatic("Bounding box", SiFormat.fovExtents(h.x(), h.y(), h.z()))
                        );
                    }
                }
            }
        };
        refresh.run();
        refreshGammaFromCircuit();
        // Re-resolve when project state changes (the linked substance docs
        // may be edited).
        paneContext.session().project.state().currentProperty()
            .addListener((obs, o, n) -> {
                refresh.run();
                refreshGammaFromCircuit();
            });
        box.getChildren().add(content);
        return box;
    }

    /**
     * Push γ from the first continuous-magnetisation substance (if any) into
     * the {@link ConfigStore} so the Larmor-frequency binding has a real
     * value to multiply B₀ by. {@code NaN} when no Bloch substance — the
     * Larmor display then renders "—" instead of a proton-default lie.
     */
    private void refreshGammaFromCircuit() {
        // Prefer a continuous-magnetisation substance (MRI proton, etc.);
        // fall back to the first NV ensemble's electron γ so an NV-only
        // circuit still gets a sensible Larmor display instead of NaN.
        double gamma = Double.NaN;
        for (var doc : resolveSubstances()) {
            if (doc.substance() instanceof ContinuousMagnetisation cm) {
                gamma = cm.gammaRadPerSecPerTesla();
                break;
            }
        }
        if (Double.isNaN(gamma)) {
            for (var doc : resolveSubstances()) {
                if (doc.substance() instanceof NvEnsemble nv) {
                    gamma = nv.physics().gammaRadPerSecPerTesla();
                    break;
                }
            }
        }
        store.gammaRadPerSecPerTesla.set(gamma);
    }

    /** Substances referenced by Substance blocks in the current circuit. */
    private List<SubstanceDocument> resolveSubstances() {
        var state = paneContext.session().project.project();
        if (state == null) return List.of();
        var cfg = currentConfig();
        if (cfg == null || cfg.circuitId() == null) return List.of();
        var circuit = state.circuit(cfg.circuitId());
        if (circuit == null) return List.of();
        var out = new ArrayList<SubstanceDocument>();
        for (var c : circuit.components()) {
            if (c instanceof CircuitComponent.Substance block) {
                var doc = state.substance(block.substanceDocId());
                if (doc != null) out.add(doc);
            }
        }
        return out;
    }

    private SimulationConfig currentConfig() {
        var state = paneContext.session().project.project();
        if (state == null) return null;
        var doc = state.simulation(document.id());
        return doc == null ? null : doc.config();
    }

    private Node buildReferenceTab() {
        var box = new VBox(10);
        box.getChildren().add(sectionTitle("Rotating frame"));
        box.getChildren().add(rowLabelled("Reference B₀", numberField(-50, 50, 0.001).bindBidirectional(store.referenceB0Tesla), "T"));

        // dt has its own setup so we can guard against zero/negative.
        var dt = numberField(1e-12, 1e-2, 1e-7);
        dt.setValue(store.dtSeconds.get());
        dt.valueProperty().addListener((obs, o, n) -> { if (n != null && n.doubleValue() > 0) store.dtSeconds.set(n.doubleValue()); });
        store.dtSeconds.addListener((obs, o, n) -> dt.setValueQuiet(n.doubleValue()));
        box.getChildren().add(rowLabelled("Time step dt", dt, "s"));

        var larmor = new Label();
        larmor.textProperty().bind(fmt(store.larmorHz, v -> String.format("ωₛ / 2π = %s", formatFrequencyShort(v) + frequencyUnit(v))));
        larmor.getStyleClass().add("cfg-row-hint");
        box.getChildren().add(larmor);

        var nyquist = new Label();
        nyquist.textProperty().bind(fmt(store.nyquistHz, v -> String.format("Nyquist = %s", formatFrequencyShort(v) + frequencyUnit(v))));
        nyquist.getStyleClass().add("cfg-row-hint");
        box.getChildren().add(nyquist);

        return box;
    }

    private Node buildSchematicTab() {
        var stateMgr = paneContext.session().state;
        var id = store.circuitId.get();
        // Ensure the simconfig has a real circuit. If unbound, mint a fresh
        // empty one and link the simconfig to it — the schematic editor
        // always operates on a real {@link CircuitDocument} in state.
        if (id == null || stateMgr.current().circuit(id) == null) {
            var freshId = new ProjectNodeId("circuit-" + java.util.UUID.randomUUID());
            var fresh = CircuitDocument.empty(freshId, document.name() + " circuit");
            stateMgr.dispatch(new Mutation(
                Scope.indexed(Scope.root(), "circuits", freshId),
                null, fresh,
                "Create circuit", Instant.now(), "simconfig-editor",
                Mutation.Category.STRUCTURAL));
            // Re-link the simconfig — relying on RefIntegrity isn't right here:
            // we WANT the FK set, not cleared.
            store.circuitId.set(freshId);
            id = freshId;
        }
        circuitSession = new CircuitEditSession(stateMgr, id);

        schematicPane = new SchematicPane(circuitSession,
            stateMgr::current,
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

    /**
     * Every config edit dispatches a structured Mutation through the unified
     * state manager. Autosave handles the disk write; undo/redo come from the
     * global mutation log (scoped to this simulation's id).
     */
    private void onConfigChanged(SimulationConfig oldC, SimulationConfig newC) {
        if (oldC == null || Objects.equals(oldC, newC)) return;
        var stateMgr = paneContext.session().state;
        var existing = stateMgr.current().simulation(document.id());
        if (existing == null) return;
        var updated = new SimulationConfigDocument(existing.id(), existing.name(), newC);
        var scope = Scope.indexed(Scope.root(), "simulations", document.id());
        stateMgr.dispatch(new Mutation(scope, existing, updated,
            "Edit simulation config", Instant.now(), "simconfig-editor",
            Mutation.Category.CONTENT));
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
        l.setPrefWidth(180);
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
        // Pin the label column so the value column's width changes don't
        // shuffle the label horizontally as numbers grow / shrink. Without
        // this, every "1.2 mT" → "12.34 µT" transition jitters the row.
        l.setPrefWidth(160);
        l.setMinWidth(160);
        var v = new Label();
        v.getStyleClass().add("cfg-kv-value");
        v.textProperty().bind(value);
        var row = new HBox(8, l, v);
        row.getStyleClass().add("cfg-kv");
        return row;
    }

    private Node kvStatic(String label, String value) {
        return kvBound(label, new SimpleStringProperty(value));
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
        if (Double.isNaN(hz)) return "—";
        double abs = Math.abs(hz);
        if (abs == 0) return "0";
        if (abs >= 1e9) return String.format("%.2f", hz / 1e9);
        if (abs >= 1e6) return String.format("%.2f", hz / 1e6);
        if (abs >= 1e3) return String.format("%.2f", hz / 1e3);
        return String.format("%.2f", hz);
    }

    private static String frequencyUnit(double hz) {
        if (Double.isNaN(hz)) return "";
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
        if (abs >= 1e-6) return String.format("%.3f μs", s * 1e6);
        return String.format("%.3f ns", s * 1e9);
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
        if (abs >= 1e-6) return " μs";
        return " ns";
    }

}
