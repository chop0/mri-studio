package ax.xz.mri.ui.eigenfield;

import ax.xz.mri.dsl.EigenfieldEngine;
import ax.xz.mri.dsl.ScriptCompileException;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.state.DocumentEditor;
import ax.xz.mri.state.Mutation;
import ax.xz.mri.state.Scope;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

/**
 * Document editor for an {@link EigenfieldDocument}. Left: DSL source. Right:
 * live 3D vector field preview.
 *
 * <p>The document is a simple (id, name, description, script) record — no
 * preset enum, no hidden mode. The editor just edits those four fields.
 * Text edits are debounced (200 ms) before recompilation to keep typing
 * smooth; compile errors appear in the status strip with line/column.
 */
public final class EigenfieldEditorPane extends WorkbenchPane {
    private static final Duration COMPILE_DEBOUNCE = Duration.millis(200);

    private final DocumentEditor<EigenfieldDocument> editor;
    /** Cached snapshot of {@code editor.value()} — always equals it. */
    private EigenfieldDocument document;

    private final TextArea scriptEditor = new TextArea();
    private final TextField nameField = new TextField();
    private final TextField descriptionField = new TextField();
    private final TextField unitsField = new TextField();
    private final Label statusLabel = new Label("Ready");
    private final Button compileButton = new Button("Compile");
    /** Full-line band painted over {@link #scriptEditor} at the compile-error line. */
    private final Region errorMarker = new Region();
    private final StackPane scriptStack = new StackPane();
    private static final double LINE_HEIGHT_PX = 14.5;
    private static final double EDITOR_TOP_PAD_PX = 4;

    private final EigenfieldPreviewCanvas preview = new EigenfieldPreviewCanvas();

    private final Timeline compileDebounce = new Timeline();
    private boolean suppressScriptListener;
    private Runnable onTitleChanged;
    /**
     * Set after {@link #hydrateFromDocument()} and consumed by the next
     * successful {@link #compileScript()}: the first compile of a freshly-
     * loaded document auto-detects an appropriate half-extent for the
     * preview canvas so eigenfields with sub-micron or kilometre-scale
     * features render visibly out of the box. Subsequent recompiles do
     * <em>not</em> override the user's manual scale choice — they only
     * re-bind the script.
     */
    private boolean autoFitOnNextCompile;

    public EigenfieldEditorPane(PaneContext paneContext, EigenfieldDocument document) {
        super(paneContext);
        var stateMgr = paneContext.session().state;
        var scope = Scope.indexed(Scope.root(), "eigenfields", document.id());
        // Ensure the doc lives in state — first-open from the explorer dispatches it.
        if (stateMgr.current().eigenfield(document.id()) == null) {
            stateMgr.dispatch(Mutation.structural(scope, null, document, "Create eigenfield"));
        }
        this.editor = new DocumentEditor<>(stateMgr, scope, "eigenfield-editor", EigenfieldDocument.class);
        this.document = editor.value();
        editor.valueProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            this.document = n;
            if (suppressScriptListener) return;
            // Re-hydrate UI from new state (covers undo / external edits).
            hydrateFromDocument();
            if (o == null || !java.util.Objects.equals(o.script(), n.script())) {
                compileScript();
            }
            setPaneTitle("Eigenfield: " + n.name());
            notifyTitleChanged();
        });
        setPaneTitle("Eigenfield: " + document.name());

        var meta = buildMetaStrip();
        var editorPane = buildEditorSide();
        var previewPane = buildPreviewSide();

        var split = new SplitPane(editorPane, previewPane);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.46);

        var body = new BorderPane();
        body.setTop(meta);
        body.setCenter(split);
        body.setPadding(new Insets(6));

        body.setFocusTraversable(true);
        body.setOnKeyPressed(event -> {
            if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN).match(event)) {
                redo();
                event.consume();
            } else if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN).match(event)) {
                undo();
                event.consume();
            }
        });

        setPaneContent(body);

        hydrateFromDocument();
        autoFitOnNextCompile = true;
        compileScript();
    }

    // --- UI ---

    private Node buildMetaStrip() {
        nameField.setPromptText("Eigenfield name");
        nameField.setPrefColumnCount(24);
        nameField.focusedProperty().addListener((obs, o, focused) -> {
            if (!focused) applyName(nameField.getText());
        });
        nameField.setOnAction(e -> applyName(nameField.getText()));

        descriptionField.setPromptText("Description");
        HBox.setHgrow(descriptionField, Priority.ALWAYS);
        descriptionField.focusedProperty().addListener((obs, o, focused) -> {
            if (!focused) applyDescription(descriptionField.getText());
        });
        descriptionField.setOnAction(e -> applyDescription(descriptionField.getText()));

        unitsField.setPromptText("T · T/m · Hz · …");
        unitsField.setPrefColumnCount(7);
        unitsField.setTooltip(new javafx.scene.control.Tooltip(
            "Physical units label for UI readouts. The script itself is " +
            "dimensionless — each coil scales its sensitivity (T/A) to pick " +
            "the actual magnitude."));
        unitsField.focusedProperty().addListener((obs, o, focused) -> {
            if (!focused) applyUnits(unitsField.getText());
        });
        unitsField.setOnAction(e -> applyUnits(unitsField.getText()));

        var row = new HBox(8,
            new Label("Name"), nameField,
            new Separator(Orientation.VERTICAL),
            new Label("Units"), unitsField,
            new Separator(Orientation.VERTICAL),
            new Label("Description"), descriptionField);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 2, 8, 2));
        return row;
    }

    private Node buildEditorSide() {
        var header = new Label("DSL (Java — return Vec3.of(x, y, z))");
        header.getStyleClass().add("section-header");

        scriptEditor.setFont(Font.font("Menlo", FontWeight.NORMAL, 12));
        scriptEditor.setWrapText(false);
        scriptEditor.setPrefColumnCount(60);
        scriptEditor.textProperty().addListener((obs, o, n) -> onScriptEdited());

        errorMarker.setBackground(new javafx.scene.layout.Background(
            new javafx.scene.layout.BackgroundFill(
                javafx.scene.paint.Color.web("#b3261e", 0.18), null, null)));
        errorMarker.setBorder(new javafx.scene.layout.Border(
            new javafx.scene.layout.BorderStroke(
                javafx.scene.paint.Color.web("#b3261e", 0.65),
                javafx.scene.layout.BorderStrokeStyle.SOLID,
                null, new javafx.scene.layout.BorderWidths(0, 0, 0, 3))));
        errorMarker.setMouseTransparent(true);
        errorMarker.setVisible(false);
        errorMarker.setPrefHeight(LINE_HEIGHT_PX);
        errorMarker.setMinHeight(LINE_HEIGHT_PX);
        errorMarker.setMaxHeight(LINE_HEIGHT_PX);
        StackPane.setAlignment(errorMarker, Pos.TOP_LEFT);
        errorMarker.prefWidthProperty().bind(scriptEditor.widthProperty().subtract(16));
        scriptStack.getChildren().setAll(scriptEditor, errorMarker);
        VBox.setVgrow(scriptStack, Priority.ALWAYS);

        compileButton.setOnAction(e -> compileScript());
        compileButton.setFocusTraversable(false);

        var statusRow = new HBox(8, statusLabel, new Region(), compileButton);
        HBox.setHgrow(statusRow.getChildren().get(1), Priority.ALWAYS);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        var help = new Label("""
            Body of evaluate(double x, double y, double z) → Vec3.
            (x, y, z) are in metres.  Math.* is imported (sin, cos, sqrt, PI, …).
            Return Vec3.of(bx, by, bz) — the normalised field at unit amplitude.
            Ctrl/Cmd+S: save · Ctrl/Cmd+Z: undo · Ctrl/Cmd+Shift+Z: redo.""");
        help.setStyle("-fx-text-fill: #707070; -fx-font-size: 10.5;");
        help.setPadding(new Insets(6, 0, 0, 0));

        var editorBox = new VBox(4, header, scriptStack, statusRow, help);
        VBox.setVgrow(scriptStack, Priority.ALWAYS);
        editorBox.setPadding(new Insets(4));
        return editorBox;
    }

    private Node buildPreviewSide() {
        var header = new Label("Live 3D preview");
        header.getStyleClass().add("section-header");

        var frontBtn = new Button("Front");
        frontBtn.setOnAction(e -> preview.setPreset(0, 0));
        var topBtn = new Button("Top");
        topBtn.setOnAction(e -> preview.setPreset(0, Math.PI / 2));
        var isoBtn = new Button("ISO");
        isoBtn.setOnAction(e -> preview.setPreset(0.6, 0.5));
        var resetBtn = new Button("Reset");
        resetBtn.setOnAction(e -> preview.resetView());

        var densitySlider = new Slider(3, 15, preview.samplesPerAxisProperty().get());
        densitySlider.setMajorTickUnit(2);
        densitySlider.setMinorTickCount(1);
        densitySlider.setShowTickMarks(true);
        densitySlider.setBlockIncrement(1);
        densitySlider.setSnapToTicks(true);
        densitySlider.setPrefWidth(120);
        densitySlider.valueProperty().addListener((obs, o, n) ->
            preview.samplesPerAxisProperty().set(n.intValue()));

        // Log-scale half-extent slider: slider value = log10(extent / 1 m), so
        // the range [-12, 2] covers 1 pm to 100 m — wide enough for any
        // eigenfield from a sub-nanometre NV spin texture to a metre-scale
        // Helmholtz pair. Linear sliders can't span that.
        double initialLog = Math.log10(Math.max(preview.halfExtentMProperty().get(), 1e-12));
        var extentSlider = new Slider(-12, 2, initialLog);
        extentSlider.setPrefWidth(160);
        extentSlider.setShowTickMarks(false);
        var extentValueLabel = new Label();
        extentValueLabel.setMinWidth(64);
        extentValueLabel.setStyle("-fx-font-family: monospace; -fx-text-fill: -studio-text-secondary;");
        Runnable refreshLabel = () -> {
            var pref = ax.xz.mri.util.SiFormat.pickPrefix(
                Math.max(preview.halfExtentMProperty().get(), 1e-15), "m");
            double display = preview.halfExtentMProperty().get() * pref.scale();
            extentValueLabel.setText(String.format("±%.2f %s", display, pref.label()));
        };
        // Slider → property (user dragging the slider).
        boolean[] slaving = {false};
        extentSlider.valueProperty().addListener((obs, o, n) -> {
            if (slaving[0]) return;
            slaving[0] = true;
            preview.halfExtentMProperty().set(Math.pow(10, n.doubleValue()));
            refreshLabel.run();
            slaving[0] = false;
        });
        // Property → slider (auto-fit or external set).
        preview.halfExtentMProperty().addListener((obs, o, n) -> {
            if (slaving[0]) return;
            slaving[0] = true;
            extentSlider.setValue(Math.log10(Math.max(n.doubleValue(), 1e-12)));
            refreshLabel.run();
            slaving[0] = false;
        });
        refreshLabel.run();

        var autoFitBtn = new Button("Auto-fit");
        autoFitBtn.setTooltip(new javafx.scene.control.Tooltip(
            "Detect a half-extent that frames the script's dominant spatial feature"));
        autoFitBtn.setOnAction(e -> preview.autoFitHalfExtent());

        var colourCheck = new CheckBox("Colour by |B|");
        colourCheck.selectedProperty().bindBidirectional(preview.colourByMagnitudeProperty());

        var boxCheck = new CheckBox("Box");
        boxCheck.selectedProperty().bindBidirectional(preview.showBoundingBoxProperty());

        var axesCheck = new CheckBox("Axes");
        axesCheck.selectedProperty().bindBidirectional(preview.showAxesProperty());

        var toolbar = new HBox(6,
            frontBtn, topBtn, isoBtn, resetBtn,
            new Separator(Orientation.VERTICAL),
            new Label("Samples"), densitySlider,
            new Separator(Orientation.VERTICAL),
            new Label("Half-extent"), extentSlider, extentValueLabel, autoFitBtn,
            new Separator(Orientation.VERTICAL),
            colourCheck, boxCheck, axesCheck);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(4, 0, 4, 0));

        var toolbarScroll = new ScrollPane(toolbar);
        toolbarScroll.setFitToHeight(true);
        toolbarScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        toolbarScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        toolbarScroll.setPrefViewportHeight(34);

        var box = new VBox(4, header, toolbarScroll, preview);
        VBox.setVgrow(preview, Priority.ALWAYS);
        box.setPadding(new Insets(4));
        return box;
    }

    // --- State ---

    private void hydrateFromDocument() {
        suppressScriptListener = true;
        try {
            // Only setText when the value actually changed. TextArea.setText
            // resets the caret to position 0 even when the new value matches
            // the current — typing a single character would otherwise yank
            // the cursor back to the top of the script every keystroke.
            setIfChanged(nameField, document.name());
            setIfChanged(descriptionField, document.description() == null ? "" : document.description());
            setIfChanged(unitsField, document.units());
            setIfChanged(scriptEditor, document.script());
        } finally {
            suppressScriptListener = false;
        }
    }

    private static void setIfChanged(TextField field, String value) {
        if (!java.util.Objects.equals(field.getText(), value)) field.setText(value);
    }
    private static void setIfChanged(TextArea area, String value) {
        if (!java.util.Objects.equals(area.getText(), value)) area.setText(value);
    }

    private void onScriptEdited() {
        if (suppressScriptListener) return;
        String next = scriptEditor.getText();
        if (next.equals(document.script())) return;
        editor.apply(d -> d.withScript(next), "Edit script");
        compileDebounce.stop();
        compileDebounce.getKeyFrames().setAll(new KeyFrame(COMPILE_DEBOUNCE, e -> compileScript()));
        compileDebounce.playFromStart();
    }

    private void applyName(String rawName) {
        var newName = rawName == null ? "" : rawName.strip();
        if (newName.isBlank() || newName.equals(document.name())) return;
        editor.apply(d -> d.withName(newName), "Edit name");
        paneContext.session().project.explorer.refresh();
    }

    private void applyDescription(String rawDescription) {
        var newDescription = rawDescription == null ? "" : rawDescription;
        if (newDescription.equals(document.description())) return;
        editor.apply(d -> d.withDescription(newDescription), "Edit description");
    }

    private void applyUnits(String rawUnits) {
        var newUnits = rawUnits == null ? "" : rawUnits.strip();
        if (newUnits.equals(document.units())) return;
        editor.apply(d -> d.withUnits(newUnits), "Edit units");
    }

    private void compileScript() {
        String source = document.script();
        try {
            var compiled = EigenfieldEngine.compile(source);
            try {
                compiled.evaluate(0, 0, 0);
            } catch (Throwable evalFail) {
                setStatus("Runtime error at origin: " + evalFail.getMessage(), true);
                preview.scriptProperty().set(null);
                clearErrorMarker();
                return;
            }
            preview.scriptProperty().set(compiled);
            setStatus("Compiled.", false);
            clearErrorMarker();
            if (autoFitOnNextCompile) {
                autoFitOnNextCompile = false;
                preview.autoFitHalfExtent();
            }
        } catch (ScriptCompileException ex) {
            preview.scriptProperty().set(null);
            setStatus(ex.shortMessage() + "  (line " + ex.line() + ", col " + ex.column() + ")", true);
            markErrorAtLine(ex.line());
        } catch (Throwable t) {
            preview.scriptProperty().set(null);
            setStatus("Compilation failed: " + t.getMessage(), true);
            clearErrorMarker();
        }
    }

    private void markErrorAtLine(int line) {
        if (line < 1) { errorMarker.setVisible(false); return; }
        double y = EDITOR_TOP_PAD_PX + (line - 1) * LINE_HEIGHT_PX;
        StackPane.setMargin(errorMarker, new Insets(y, 0, 0, 8));
        errorMarker.setVisible(true);
    }

    private void clearErrorMarker() { errorMarker.setVisible(false); }

    private void setStatus(String text, boolean error) {
        statusLabel.setText(text);
        statusLabel.setStyle(error
            ? "-fx-text-fill: #c0392b; -fx-font-weight: bold;"
            : "-fx-text-fill: #2e7d32;");
        setPaneStatus(text);
    }

    // --- Undo / redo (scoped to this eigenfield via DocumentEditor) ---

    private void undo() { editor.undo(); }
    private void redo() { editor.redo(); }

    public String tabTitle() { return document.name(); }

    public void setOnTitleChanged(Runnable callback) { this.onTitleChanged = callback; }

    private void notifyTitleChanged() {
        if (onTitleChanged != null) onTitleChanged.run();
    }

    @Override
    public void dispose() {
        compileDebounce.stop();
        preview.stop();
        super.dispose();
    }

    public EigenfieldDocument currentDocument() { return document; }
}
