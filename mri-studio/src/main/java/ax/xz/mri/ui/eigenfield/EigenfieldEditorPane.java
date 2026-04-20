package ax.xz.mri.ui.eigenfield;

import ax.xz.mri.model.simulation.dsl.EigenfieldScriptEngine;
import ax.xz.mri.model.simulation.dsl.ScriptCompileException;
import ax.xz.mri.project.EigenfieldDocument;
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
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import java.util.ArrayDeque;

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
    private static final int MAX_UNDO = 100;
    private static final Duration COMPILE_DEBOUNCE = Duration.millis(200);

    private EigenfieldDocument document;
    private EigenfieldDocument savedDocument;

    private final TextArea scriptEditor = new TextArea();
    private final TextField nameField = new TextField();
    private final TextField descriptionField = new TextField();
    private final Label statusLabel = new Label("Ready");
    private final Button compileButton = new Button("Compile");

    private final EigenfieldPreviewCanvas preview = new EigenfieldPreviewCanvas();

    private final ArrayDeque<EigenfieldDocument> undoStack = new ArrayDeque<>();
    private final ArrayDeque<EigenfieldDocument> redoStack = new ArrayDeque<>();

    private final Timeline compileDebounce = new Timeline();
    private boolean suppressScriptListener;
    private Runnable onTitleChanged;

    public EigenfieldEditorPane(PaneContext paneContext, EigenfieldDocument document) {
        super(paneContext);
        this.document = document;
        this.savedDocument = document;
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
            if (new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN).match(event)) {
                save();
                event.consume();
            } else if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN).match(event)) {
                redo();
                event.consume();
            } else if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN).match(event)) {
                undo();
                event.consume();
            }
        });

        setPaneContent(body);

        hydrateFromDocument();
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

        var row = new HBox(8,
            new Label("Name"), nameField,
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

        var editorBox = new VBox(4, header, scriptEditor, statusRow, help);
        VBox.setVgrow(scriptEditor, Priority.ALWAYS);
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

        var extentSlider = new Slider(0.02, 0.5, preview.halfExtentMProperty().get());
        extentSlider.setPrefWidth(120);
        extentSlider.valueProperty().addListener((obs, o, n) ->
            preview.halfExtentMProperty().set(n.doubleValue()));

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
            new Label("Half-extent (m)"), extentSlider,
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
            nameField.setText(document.name());
            descriptionField.setText(document.description() == null ? "" : document.description());
            scriptEditor.setText(document.script());
        } finally {
            suppressScriptListener = false;
        }
    }

    private void onScriptEdited() {
        if (suppressScriptListener) return;
        String next = scriptEditor.getText();
        if (next.equals(document.script())) return;
        pushUndo();
        document = document.withScript(next);
        persistToRepository();
        notifyTitleChanged();

        compileDebounce.stop();
        compileDebounce.getKeyFrames().setAll(new KeyFrame(COMPILE_DEBOUNCE, e -> compileScript()));
        compileDebounce.playFromStart();
    }

    private void applyName(String newName) {
        if (newName == null) newName = "";
        newName = newName.strip();
        if (newName.isBlank() || newName.equals(document.name())) return;
        pushUndo();
        document = document.withName(newName);
        setPaneTitle("Eigenfield: " + document.name());
        persistToRepository();
        paneContext.session().project.explorer.refresh();
        notifyTitleChanged();
    }

    private void applyDescription(String newDescription) {
        if (newDescription == null) newDescription = "";
        if (newDescription.equals(document.description())) return;
        pushUndo();
        document = document.withDescription(newDescription);
        persistToRepository();
        notifyTitleChanged();
    }

    private void compileScript() {
        String source = document.script();
        try {
            var compiled = EigenfieldScriptEngine.compile(source);
            try {
                compiled.evaluate(0, 0, 0);
            } catch (Throwable evalFail) {
                setStatus("Runtime error at origin: " + evalFail.getMessage(), true);
                preview.scriptProperty().set(null);
                return;
            }
            preview.scriptProperty().set(compiled);
            setStatus("Compiled.", false);
        } catch (ScriptCompileException ex) {
            preview.scriptProperty().set(null);
            setStatus(ex.shortMessage() + "  (line " + ex.line() + ", col " + ex.column() + ")", true);
        } catch (Throwable t) {
            preview.scriptProperty().set(null);
            setStatus("Compilation failed: " + t.getMessage(), true);
        }
    }

    private void setStatus(String text, boolean error) {
        statusLabel.setText(text);
        statusLabel.setStyle(error
            ? "-fx-text-fill: #c0392b; -fx-font-weight: bold;"
            : "-fx-text-fill: #2e7d32;");
        setPaneStatus(text);
    }

    // --- Undo / redo / save ---

    private void pushUndo() {
        if (undoStack.size() >= MAX_UNDO) undoStack.removeLast();
        undoStack.push(document);
        redoStack.clear();
    }

    private void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.push(document);
        document = undoStack.pop();
        hydrateFromDocument();
        persistToRepository();
        compileScript();
        notifyTitleChanged();
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(document);
        document = redoStack.pop();
        hydrateFromDocument();
        persistToRepository();
        compileScript();
        notifyTitleChanged();
    }

    public boolean isDirty() { return !document.equals(savedDocument); }

    public String tabTitle() {
        return document.name() + (isDirty() ? " *" : "");
    }

    public void setOnTitleChanged(Runnable callback) { this.onTitleChanged = callback; }

    private void notifyTitleChanged() {
        if (onTitleChanged != null) onTitleChanged.run();
    }

    public void save() {
        persistToRepository();
        paneContext.controller().saveProject();
        savedDocument = document;
        paneContext.session().project.explorer.refresh();
        notifyTitleChanged();
    }

    private void persistToRepository() {
        var repo = paneContext.session().project.repository.get();
        repo.addEigenfield(document);
        paneContext.session().project.saveProjectQuietly();
    }

    @Override
    public void dispose() {
        compileDebounce.stop();
        preview.stop();
        super.dispose();
    }

    public EigenfieldDocument currentDocument() { return document; }
}
