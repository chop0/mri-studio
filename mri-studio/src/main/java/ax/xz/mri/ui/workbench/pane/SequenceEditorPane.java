package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
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

/**
 * Sequence editor pane: segment timeline, waveform display, tool palette, undo/redo.
 * Registered as a BentoFX dockable. When the user opens a {@link SequenceDocument},
 * this pane's leaf replaces the analysis centre-shell in the dock tree.
 */
public final class SequenceEditorPane extends WorkbenchPane {
    private final SequenceEditSession editSession = new SequenceEditSession();
    private final Label sequenceNameLabel = new Label("\u2014");
    private final SegmentTimelineCanvas timelineCanvas;
    private final WaveformEditorCanvas waveformCanvas;
    private final SequenceToolPalette toolPalette;

    public SequenceEditorPane(PaneContext paneContext) {
        super(paneContext);
        setPaneTitle("Sequence Editor");

        // --- Tool palette (left) ---
        toolPalette = new SequenceToolPalette(editSession);

        // --- Canvases (ResizableCanvas handles its own sizing via isResizable/resize) ---
        timelineCanvas = new SegmentTimelineCanvas(editSession);
        waveformCanvas = new WaveformEditorCanvas(editSession);

        // --- Header bar ---
        sequenceNameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13;");

        var undoButton = new Button("\u21a9");
        undoButton.setTooltip(new Tooltip("Undo (\u2318Z)"));
        undoButton.disableProperty().bind(editSession.canUndoProperty().not());
        undoButton.setOnAction(event -> editSession.undo());
        undoButton.setFocusTraversable(false);

        var redoButton = new Button("\u21aa");
        redoButton.setTooltip(new Tooltip("Redo (\u2318\u21e7Z)"));
        redoButton.disableProperty().bind(editSession.canRedoProperty().not());
        redoButton.setOnAction(event -> editSession.redo());
        redoButton.setFocusTraversable(false);

        var saveButton = new Button("Save");
        saveButton.setTooltip(new Tooltip("Save sequence changes"));
        saveButton.setOnAction(event -> saveSequence());
        saveButton.setFocusTraversable(false);

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var headerBar = new HBox(8, sequenceNameLabel, spacer, undoButton, redoButton, new Separator(), saveButton);
        headerBar.setAlignment(Pos.CENTER_LEFT);
        headerBar.setPadding(new Insets(4, 8, 4, 8));

        // --- Timeline strip (fixed height; StackPane lets ResizableCanvas fill it) ---
        var timelineHolder = new StackPane(timelineCanvas);
        timelineHolder.setPrefHeight(80);
        timelineHolder.setMinHeight(80);
        timelineHolder.setMaxHeight(80);

        // --- Waveform area (fills remaining vertical space) ---
        var waveformHolder = new StackPane(waveformCanvas);
        VBox.setVgrow(waveformHolder, Priority.ALWAYS);

        // --- Preview placeholder ---
        var previewPlaceholder = new Label("Simulation preview \u2014 coming in Phase 5");
        previewPlaceholder.setStyle("-fx-text-fill: #999; -fx-font-style: italic; -fx-padding: 12;");
        previewPlaceholder.setAlignment(Pos.CENTER);
        previewPlaceholder.setPrefHeight(100);
        previewPlaceholder.setMinHeight(100);
        previewPlaceholder.setMaxHeight(100);
        previewPlaceholder.setMaxWidth(Double.MAX_VALUE);

        // --- Centre content ---
        var centre = new VBox(headerBar, new Separator(), timelineHolder, new Separator(), waveformHolder, new Separator(), previewPlaceholder);

        // --- Root layout ---
        var root = new BorderPane();
        root.setLeft(toolPalette);
        root.setCenter(centre);

        setPaneContent(root);

        // --- Keyboard shortcuts ---
        root.setOnKeyPressed(event -> {
            if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN).match(event)) {
                editSession.redo();
                event.consume();
            } else if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN).match(event)) {
                editSession.undo();
                event.consume();
            }
        });
        root.setFocusTraversable(true);
    }

    /** Load a sequence document into the editor. */
    public void open(SequenceDocument document) {
        editSession.open(document);
        sequenceNameLabel.setText("Sequence: " + document.name());
    }

    /** Whether the editor has unsaved changes. */
    public boolean isDirty() {
        return editSession.isDirty();
    }

    /** Save changes back to the project repository. */
    private void saveSequence() {
        var updated = editSession.toDocument();
        var repo = paneContext.session().project.repository.get();
        repo.removeSequence(updated.id());
        repo.addSequence(updated);
        editSession.open(updated); // reset dirty state
        paneContext.session().project.explorer.refresh();
    }

    @Override
    public void dispose() {
        timelineCanvas.dispose();
        waveformCanvas.dispose();
    }
}
