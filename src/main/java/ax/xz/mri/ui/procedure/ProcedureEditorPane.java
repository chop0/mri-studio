package ax.xz.mri.ui.procedure;

import module ax.xz.mri;
import module javafx.controls;
import module javafx.graphics;

// Non-exported types — module ax.xz.mri only surfaces the exported packages.
import ax.xz.mri.project.ProcedureDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.state.DocumentEditor;
import ax.xz.mri.state.Scope;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Document editor for a {@link ProcedureDocument}.
 *
 * <p>Layout:
 *
 * <ul>
 *   <li><b>Header strip</b> — name (inline-editable) + active simulation-context
 *       label + status badge ("Idle"/script status text/"Stopped"/summary).</li>
 *   <li><b>Toolbar</b> — Run / Stop, Clear log, progress bar.</li>
 *   <li><b>Body</b> — horizontal split with two equally-weighted columns:
 *       the Source editor on the left (fills full height), the Outputs +
 *       Log tabs on the right.</li>
 *   <li><b>Status bar</b> at the bottom — compile-status + live cursor info.</li>
 * </ul>
 *
 * <p>The Identification card was removed — the name moved into the header
 * strip where it belongs (matches every other editor in the studio).
 */
public final class ProcedureEditorPane extends WorkbenchPane {
    private static final Duration COMPILE_DEBOUNCE = Duration.millis(200);
    /**
     * Approximate vertical pitch of one line of the monospaced 12-pt
     * source editor. Used to place the error-line highlight band — JavaFX
     * {@link TextArea} doesn't expose line geometry directly, so we
     * estimate from font metrics and accept the occasional half-pixel
     * misalignment as the cost of avoiding a custom RichTextFX dependency.
     */
    private static final double LINE_HEIGHT_PX = 14.5;
    /** Top-edge inset of the text inside the {@link TextArea}'s content region. */
    private static final double EDITOR_TOP_PAD_PX = 4;

    private final DocumentEditor<ProcedureDocument> editor;
    private final ProjectNodeId documentId;

    /* Source editor */
    private final TextArea codeArea = new TextArea();
    /**
     * Full-line band painted over the editor at a compile-error's line. A
     * thin Polygon triangle in the gutter (the previous design) was too
     * subtle to spot; a translucent red band that spans the whole line —
     * the IDE-standard squiggle isn't possible on a plain JavaFX TextArea —
     * is unambiguous.
     */
    private final Region errorMarker = new Region();
    private final StackPane editorContainer = new StackPane();
    private final Timeline compileDebounce;
    private final Label compileStatus = new Label("");
    private final SimpleStringProperty compileStatusText = new SimpleStringProperty("");

    /* Header */
    private final TextField nameField = new TextField();
    private final Label headerContextLabel = new Label();
    private final Label headerStatusLabel = new Label("Idle");

    /* Harness */
    private final Button runBtn = new Button("Run");
    private final Button stopBtn = new Button("Stop");
    private final Button clearLogBtn = new Button("Clear log");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final SimpleBooleanProperty harnessRunning = new SimpleBooleanProperty(false);
    private final AtomicReference<ScriptHarness> activeHarness = new AtomicReference<>();

    /* Outputs */
    private final ListView<String> tickLog = new ListView<>();
    private final Map<String, Visualisation> liveViz = new LinkedHashMap<>();
    /**
     * Per-visualisation rendered card + in-place updater. The script
     * harness can pulse {@link #renderOutputs} 60×/s; if every tick rebuilt
     * the chart nodes from scratch (the previous behaviour) the user saw
     * a constant flicker as JavaFX tore down + relayed out axes, series, and
     * the sigma-band overlay. Now we keep the chart node stable and only
     * mutate the data inside.
     */
    private final Map<String, VisualisationRenderer.Rendered> renderedById = new LinkedHashMap<>();
    private final VBox outputsBox = new VBox(14);
    private final Label outputsEmpty = new Label("No outputs yet — run the script to see live charts.");

    public ProcedureEditorPane(PaneContext paneContext, ProcedureDocument document) {
        super(paneContext);
        setPaneTitle("Procedure: " + document.name());

        this.documentId = document.id();
        this.editor = new DocumentEditor<>(
            paneContext.session().state,
            Scope.indexed(Scope.root(), "procedures", documentId),
            "procedure-editor",
            ProcedureDocument.class);

        nameField.setText(document.name());
        nameField.setPrefColumnCount(32);
        nameField.getStyleClass().add("editor-page-name-field");
        nameField.setStyle("-fx-font-size: 13.5; -fx-font-weight: 600; -fx-background-color: transparent; "
            + "-fx-padding: 1 6 1 0; -fx-border-color: transparent;");
        nameField.focusedProperty().addListener((obs, o, focused) -> {
            if (focused) {
                nameField.setStyle("-fx-font-size: 13.5; -fx-font-weight: 600; -fx-background-color: -studio-surface-2; "
                    + "-fx-padding: 1 6 1 6; -fx-border-color: -studio-border-subtle;");
            } else {
                nameField.setStyle("-fx-font-size: 13.5; -fx-font-weight: 600; -fx-background-color: transparent; "
                    + "-fx-padding: 1 6 1 0; -fx-border-color: transparent;");
                applyName();
            }
        });
        nameField.setOnAction(e -> applyName());

        codeArea.setText(document.source());
        codeArea.setFont(Font.font("Monospaced", 12));
        codeArea.setStyle("-fx-control-inner-background: #fbfcfd; "
            + "-fx-border-color: -studio-border-subtle; -fx-border-width: 1; "
            + "-fx-background-color: -studio-surface;");

        compileStatus.textProperty().bind(compileStatusText);
        compileStatus.setStyle("-fx-font-size: 11;");

        compileDebounce = new Timeline(new KeyFrame(COMPILE_DEBOUNCE, e -> recompile()));
        compileDebounce.setCycleCount(1);
        codeArea.textProperty().addListener((obs, oldV, newV) -> {
            compileDebounce.stop();
            compileDebounce.playFromStart();
            editor.apply(d -> d.withSource(newV), "Edit procedure source");
        });

        errorMarker.setBackground(new javafx.scene.layout.Background(
            new javafx.scene.layout.BackgroundFill(
                Color.web("#b3261e", 0.18), null, null)));
        errorMarker.setBorder(new javafx.scene.layout.Border(
            new javafx.scene.layout.BorderStroke(
                Color.web("#b3261e", 0.65), javafx.scene.layout.BorderStrokeStyle.SOLID,
                null, new javafx.scene.layout.BorderWidths(0, 0, 0, 3))));
        errorMarker.setMouseTransparent(true);
        errorMarker.setVisible(false);
        errorMarker.setPrefHeight(LINE_HEIGHT_PX);
        errorMarker.setMinHeight(LINE_HEIGHT_PX);
        errorMarker.setMaxHeight(LINE_HEIGHT_PX);
        StackPane.setAlignment(errorMarker, Pos.TOP_LEFT);
        errorMarker.prefWidthProperty().bind(codeArea.widthProperty().subtract(16));

        // Buttons: pin minimum widths so labels never collapse to "...".
        for (var b : new Button[]{runBtn, stopBtn, clearLogBtn}) {
            b.setMinWidth(Region.USE_PREF_SIZE);
            b.getStyleClass().add("seq-toolbar-button");
        }
        runBtn.setOnAction(e -> runScript());
        runBtn.setDefaultButton(true);
        runBtn.getStyleClass().add("primary");
        stopBtn.setOnAction(e -> {
            var h = activeHarness.get();
            if (h != null) h.stop();
        });
        clearLogBtn.setOnAction(e -> { tickLog.getItems().clear(); resetOutputs(); });
        runBtn.disableProperty().bind(harnessRunning);
        stopBtn.disableProperty().bind(harnessRunning.not());

        progressBar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progressBar, Priority.ALWAYS);
        progressBar.setPrefHeight(8);
        progressBar.setStyle("-fx-accent: #1a73e8;");
        harnessRunning.addListener((obs, o, n) -> {
            if (!n) progressBar.setProgress(0);
        });

        outputsEmpty.setStyle("-fx-text-fill: -studio-text-muted; -fx-font-size: 11.5; -fx-padding: 40 0 0 0;");
        outputsEmpty.setAlignment(Pos.CENTER);
        outputsBox.setPadding(new Insets(8, 12, 12, 12));
        outputsBox.setAlignment(Pos.TOP_CENTER);
        resetOutputs();

        var split = new SplitPane(buildEditorColumn(), buildOutputsColumn());
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.42);
        SplitPane.setResizableWithParent(split.getItems().get(0), true);

        var root = new BorderPane();
        root.setTop(new VBox(buildHeader(), buildToolbar()));
        root.setCenter(split);
        setPaneContent(root);

        editor.valueProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            setPaneTitle("Procedure: " + n.name());
            paintHeader(n);
            paneContext.session().project.explorer.refresh();
        });
        paneContext.session().project.inspector.inspectedNodeId
            .addListener((obs, o, n) -> refreshContextLabel());
        paneContext.session().state.currentProperty()
            .addListener((obs, o, n) -> refreshContextLabel());

        paintHeader(document);
        refreshContextLabel();
        Platform.runLater(this::recompile);
    }

    /* ── Header strip ─────────────────────────────────────────────────── */

    private Node buildHeader() {
        var strip = new HBox(8);
        strip.getStyleClass().add("editor-page-header");
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.setPadding(new Insets(8, 12, 8, 12));

        var sep = new javafx.scene.control.Separator(javafx.geometry.Orientation.VERTICAL);
        sep.getStyleClass().add("editor-page-separator");
        headerContextLabel.setStyle("-fx-font-size: 11; -fx-text-fill: #5d6470;");
        headerContextLabel.setMinWidth(Region.USE_PREF_SIZE);

        var rightSpacer = new Region();
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);

        headerStatusLabel.setStyle("-fx-font-size: 11; -fx-text-fill: #2b2f37; "
            + "-fx-padding: 2 8 2 8; -fx-background-color: #e8ebee; "
            + "-fx-background-radius: 3;");
        // Status badge can hold long convergence messages; truncate with ellipsis
        // and surface the full text in a tooltip so the user can hover to read.
        headerStatusLabel.setMaxWidth(360);
        headerStatusLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

        strip.getChildren().setAll(
            nameField, sep, headerContextLabel, rightSpacer, headerStatusLabel);
        return strip;
    }

    private void paintHeader(ProcedureDocument doc) {
        // Only setText when the value differs — calling TextField.setText
        // on the focused name field with the same value would still reset
        // the caret and lose any in-flight inline rename.
        if (!java.util.Objects.equals(nameField.getText(), doc.name())) {
            nameField.setText(doc.name());
        }
    }

    private void refreshContextLabel() {
        var session = paneContext.session();
        var inspectedId = session.project.inspector.inspectedNodeId.get();
        SimulationConfigDocument cfg = null;
        if (inspectedId != null
            && session.state.current().node(inspectedId) instanceof SimulationConfigDocument c) {
            cfg = c;
        }
        if (cfg == null) {
            var first = session.state.current().simulationIds().stream().findFirst().orElse(null);
            if (first != null) cfg = session.state.current().simulation(first);
        }
        headerContextLabel.setText(cfg == null
            ? "No simulation config — select one in the Explorer"
            : "Sim: " + cfg.name());
    }

    /* ── Toolbar ──────────────────────────────────────────────────────── */

    private Node buildToolbar() {
        var bar = new HBox(6);
        bar.getStyleClass().add("shell-tool-strip");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 12, 4, 12));

        var sep1 = new Separator(Orientation.VERTICAL);
        var sep2 = new Separator(Orientation.VERTICAL);

        bar.getChildren().addAll(
            runBtn, stopBtn, sep1, clearLogBtn, sep2,
            progressBar
        );
        return bar;
    }

    /* ── Editor column ────────────────────────────────────────────────── */

    private Node buildEditorColumn() {
        editorContainer.getChildren().setAll(codeArea, errorMarker);
        StackPane.setMargin(errorMarker, new Insets(2, 0, 0, 2));
        VBox.setVgrow(editorContainer, Priority.ALWAYS);

        var sourceHeader = new HBox(8);
        sourceHeader.setAlignment(Pos.CENTER_LEFT);
        sourceHeader.setPadding(new Insets(0, 0, 4, 0));
        var sourceLabel = new Label("Source");
        sourceLabel.getStyleClass().add("editor-section-header");
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        sourceHeader.getChildren().setAll(sourceLabel, spacer, compileStatus);

        var box = new VBox(sourceHeader, editorContainer);
        box.setPadding(new Insets(10, 10, 10, 12));
        VBox.setVgrow(editorContainer, Priority.ALWAYS);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    /* ── Outputs column ───────────────────────────────────────────────── */

    private Node buildOutputsColumn() {
        tickLog.setStyle("-fx-control-inner-background: #fbfcfd; "
            + "-fx-border-color: -studio-border-subtle; -fx-border-width: 1;");
        tickLog.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? "" : item);
                setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 11; -fx-padding: 0 4 0 4;");
            }
        });

        var outputsScroll = new ScrollPane(outputsBox);
        outputsScroll.setFitToWidth(true);
        outputsScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        var outputsTab = new Tab("Outputs", outputsScroll);
        outputsTab.setClosable(false);
        var logTab = new Tab("Log", tickLog);
        logTab.setClosable(false);
        var tabs = new TabPane(outputsTab, logTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("seq-analysis-tabs");

        var box = new VBox(tabs);
        box.setPadding(new Insets(10, 12, 10, 10));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    /* ── Output rendering ─────────────────────────────────────────────── */

    private void resetOutputs() {
        liveViz.clear();
        renderedById.clear();
        outputsBox.getChildren().setAll(outputsEmpty);
    }

    /**
     * Sync the Outputs panel with {@link #liveViz}. For each visualisation
     * id: if a card has already been rendered, hand the new value to its
     * updater (in-place data mutation, no JavaFX node teardown); otherwise
     * render a fresh card and append it. Removes cards for ids that have
     * disappeared from {@code liveViz}, and swaps in {@link #outputsEmpty}
     * when nothing remains.
     */
    private void renderOutputs() {
        if (liveViz.isEmpty()) {
            renderedById.clear();
            outputsBox.getChildren().setAll(outputsEmpty);
            return;
        }
        if (outputsBox.getChildren().size() == 1 && outputsBox.getChildren().get(0) == outputsEmpty) {
            outputsBox.getChildren().clear();
        }
        for (var entry : liveViz.entrySet()) {
            var id = entry.getKey();
            var viz = entry.getValue();
            var existing = renderedById.get(id);
            if (existing == null) {
                var r = VisualisationRenderer.render(viz);
                renderedById.put(id, r);
                outputsBox.getChildren().add(r.node());
            } else {
                existing.update().accept(viz);
            }
        }
        // Drop rendered cards whose ids are no longer in liveViz.
        var iter = renderedById.entrySet().iterator();
        while (iter.hasNext()) {
            var e = iter.next();
            if (!liveViz.containsKey(e.getKey())) {
                outputsBox.getChildren().remove(e.getValue().node());
                iter.remove();
            }
        }
    }

    /* ── Lifecycle ────────────────────────────────────────────────────── */

    public void dispose() {
        var h = activeHarness.getAndSet(null);
        if (h != null) {
            h.stop();
            h.close();
        }
    }

    private void applyName() {
        var name = nameField.getText() == null ? "" : nameField.getText().strip();
        if (name.isBlank()) return;
        editor.apply(d -> d.withName(name), "Rename procedure");
    }

    private void recompile() {
        var source = codeArea.getText();
        if (source == null || source.isBlank()) {
            compileStatusText.set("(empty)");
            compileStatus.setStyle("-fx-text-fill: -studio-text-tertiary; -fx-font-size: 11;");
            clearErrorMarker();
            return;
        }
        try {
            var proc = ProcedureEngine.compile(source);
            compileStatusText.set("✓ " + proc.getClass().getSimpleName());
            compileStatus.setStyle("-fx-text-fill: -studio-success; -fx-font-size: 11;");
            clearErrorMarker();
        } catch (ScriptCompileException ex) {
            int line = ex.line(), col = ex.column();
            compileStatusText.set("✗ " + line + ":" + col + " " + ex.shortMessage());
            compileStatus.setStyle("-fx-text-fill: -studio-danger; -fx-font-size: 11;");
            markErrorAtLine(line);
        } catch (RuntimeException ex) {
            compileStatusText.set("✗ " + ex.getMessage());
            compileStatus.setStyle("-fx-text-fill: -studio-danger; -fx-font-size: 11;");
            clearErrorMarker();
        }
    }

    private void markErrorAtLine(int line) {
        if (line < 1) {
            errorMarker.setVisible(false);
            return;
        }
        // Position the band over the offending line. We deliberately do NOT
        // move the caret here — yanking the caret to the error every time
        // the debounced recompile fires is what made editing buggy code
        // feel like fighting the editor.
        double y = EDITOR_TOP_PAD_PX + (line - 1) * LINE_HEIGHT_PX;
        StackPane.setMargin(errorMarker, new Insets(y, 0, 0, 8));
        errorMarker.setVisible(true);
    }

    private void clearErrorMarker() { errorMarker.setVisible(false); }

    private void runScript() {
        Script script;
        try {
            script = ProcedureEngine.compile(codeArea.getText());
        } catch (RuntimeException ex) {
            setStatusBadge("Compile failed", true);
            return;
        }
        var session = paneContext.session();
        var inspectedId = session.project.inspector.inspectedNodeId.get();
        var inspected = inspectedId != null ? session.state.current().node(inspectedId) : null;
        SimulationConfig activeCfg = null;
        if (inspected instanceof SimulationConfigDocument cfg) activeCfg = cfg.config();
        if (activeCfg == null) {
            var first = session.state.current().simulationIds().stream().findFirst().orElse(null);
            if (first != null) activeCfg = session.state.current().simulation(first).config();
        }
        if (activeCfg == null) {
            headerStatusLabel.setText("No simulation config");
            return;
        }
        ObservationSource source = new SimulatorObservationSource(activeCfg, session.state.current());
        var harness = new ScriptHarness();
        activeHarness.set(harness);
        harnessRunning.set(true);
        tickLog.getItems().clear();
        resetOutputs();
        setStatusBadge("Running on " + source.displayName(), false);
        progressBar.setProgress(0);
        long seed = System.nanoTime();

        var circuit = session.state.current().circuit(activeCfg.circuitId());
        int channelTotal = 0;
        if (circuit != null) {
            for (var src : circuit.voltageSources()) channelTotal += src.kind().channelCount();
        }
        int chSize = Math.max(channelTotal, 1);
        var sim = new ax.xz.mri.model.simulation.SimulationCompiler()
            .compile(activeCfg,
                java.util.List.of(new ax.xz.mri.model.sequence.Segment(1e-6, 0, 1)),
                java.util.List.of(new ax.xz.mri.model.sequence.PulseSegment(
                    java.util.List.of(new ax.xz.mri.model.sequence.PulseStep(new double[chSize], 0.0)))),
                session.state.current());

        // Coalesce harness ticks so 1000 iters/sec doesn't queue 1000 FX
        // runLater calls — that's what froze the UI. The worker thread
        // appends each tick to a buffer; a single FX-thread pulse drains
        // them at most once per 30 ms, keeping the chart + log responsive
        // while the script churns at full speed.
        var pendingTicks = new java.util.concurrent.ConcurrentLinkedQueue<ScriptHarness.Tick>();
        var pulseScheduled = new java.util.concurrent.atomic.AtomicBoolean(false);
        Runnable drain = () -> {
            ScriptHarness.Tick last = null;
            ScriptHarness.Tick t;
            int newLogLines = 0;
            // Drain the queue; keep the last visualisation per id, append log lines.
            while ((t = pendingTicks.poll()) != null) {
                if (t.log() != null && !t.log().isBlank() && newLogLines < 50) {
                    var b = new StringBuilder();
                    b.append(t.log());
                    if (!t.metrics().isEmpty()) {
                        b.append("   ");
                        t.metrics().forEach((k, v) -> b.append(k).append('=').append(String.format("%.4g", v)).append("  "));
                    }
                    tickLog.getItems().add(b.toString());
                    newLogLines++;
                }
                if (!t.visualisations().isEmpty()) {
                    for (var viz : t.visualisations()) liveViz.put(viz.id(), viz);
                }
                last = t;
            }
            if (tickLog.getItems().size() > 2000) {
                tickLog.getItems().remove(0, tickLog.getItems().size() - 2000);
            }
            if (newLogLines > 0) tickLog.scrollTo(tickLog.getItems().size() - 1);
            if (last != null) {
                if (last.status() != null) setStatusBadge(last.status(), false);
                if (last.progress() != null) {
                    double p = last.progress();
                    progressBar.setProgress(Double.isNaN(p) ? ProgressBar.INDETERMINATE_PROGRESS : p);
                }
                if (!liveViz.isEmpty()) renderOutputs();
            }
            pulseScheduled.set(false);
        };

        harness.run(script, sim, source, seed, tick -> {
            pendingTicks.add(tick);
            if (pulseScheduled.compareAndSet(false, true)) {
                Platform.runLater(drain);
            }
        }).whenComplete((result, ex) -> Platform.runLater(() -> {
            drain.run();   // flush any straggler ticks before showing final status
            activeHarness.set(null);
            harnessRunning.set(false);
            if (ex != null) {
                setStatusBadge("Failed: " + ex.getMessage(), true);
            } else if (result != null) {
                progressBar.setProgress(1.0);
                setStatusBadge(result.summary().isEmpty() ? "Done" : result.summary(), false);
            } else {
                setStatusBadge("Stopped", false);
            }
            harness.close();
        }));
    }

    private void setStatusBadge(String text, boolean error) {
        headerStatusLabel.setText(text);
        // Make long messages hoverable.
        headerStatusLabel.setTooltip(new javafx.scene.control.Tooltip(text));
        if (error) {
            headerStatusLabel.setStyle("-fx-font-size: 11; -fx-text-fill: white; "
                + "-fx-padding: 2 8 2 8; -fx-background-color: #b3261e; "
                + "-fx-background-radius: 3;");
        } else if (harnessRunning.get()) {
            headerStatusLabel.setStyle("-fx-font-size: 11; -fx-text-fill: white; "
                + "-fx-padding: 2 8 2 8; -fx-background-color: #4d7e3e; "
                + "-fx-background-radius: 3;");
        } else {
            headerStatusLabel.setStyle("-fx-font-size: 11; -fx-text-fill: #2b2f37; "
                + "-fx-padding: 2 8 2 8; -fx-background-color: #e8ebee; "
                + "-fx-background-radius: 3;");
        }
    }
}
