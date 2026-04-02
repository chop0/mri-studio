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
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.ToggleButton;
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
 * Clip-based sequence editor pane with overview scrubber, multi-track canvas,
 * tool palette, and undo/redo. Clip properties are displayed in the Inspector pane.
 *
 * <p>Registered as a BentoFX dockable. When the user opens a {@link SequenceDocument},
 * this pane's leaf replaces the analysis centre-shell in the dock tree.
 */
public final class SequenceEditorPane extends WorkbenchPane {
    private final SequenceEditSession editSession = new SequenceEditSession();
    private final Label sequenceNameLabel = new Label("\u2014");
    private final SequenceOverviewBar overviewBar;
    private final ClipTrackCanvas trackCanvas;
    private final SequenceToolPalette toolPalette;
    private Runnable onTitleChanged;
    private String sequenceName = "";

    public SequenceEditorPane(PaneContext paneContext) {
        super(paneContext);
        setPaneTitle("Sequence Editor");

        // --- Tool palette (left) ---
        toolPalette = new SequenceToolPalette(editSession);

        // --- Canvases ---
        overviewBar = new SequenceOverviewBar(editSession);
        trackCanvas = new ClipTrackCanvas(editSession);

        // Wire active tool from palette to canvas
        toolPalette.setOnActiveToolChanged(() ->
            trackCanvas.setActiveCreationShape(toolPalette.activeClipShape())
        );

        // Update tab title when dirty state changes
        editSession.revision.addListener((obs, o, n) -> notifyTitleChanged());

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

        // Duration control
        var durationLabel = new Label("Duration (μs):");
        durationLabel.setStyle("-fx-font-size: 10;");
        var durationSpinner = new Spinner<Double>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(10, 100000, editSession.totalDuration.get(), 100)
        );
        durationSpinner.setEditable(true);
        durationSpinner.setPrefWidth(100);
        durationSpinner.setStyle("-fx-font-size: 10;");
        durationSpinner.setFocusTraversable(false);
        durationSpinner.valueProperty().addListener((obs, o, n) -> {
            if (n != null) editSession.setTotalDuration(n);
        });
        editSession.totalDuration.addListener((obs, o, n) ->
            durationSpinner.getValueFactory().setValue(n.doubleValue())
        );

        // Snap controls
        var snapToggle = new ToggleButton("\u2b29 Snap");
        snapToggle.setTooltip(new Tooltip("Enable/disable clip snapping"));
        snapToggle.selectedProperty().bindBidirectional(editSession.snapEnabled);
        snapToggle.setFocusTraversable(false);
        snapToggle.setStyle("-fx-font-size: 10;");

        var gridLabel = new Label("Grid:");
        gridLabel.setStyle("-fx-font-size: 10;");
        var gridSpinner = new Spinner<Double>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 1000, 0, 10)
        );
        gridSpinner.setEditable(true);
        gridSpinner.setPrefWidth(70);
        gridSpinner.setStyle("-fx-font-size: 10;");
        gridSpinner.setFocusTraversable(false);
        gridSpinner.setTooltip(new Tooltip("Snap grid size (μs). 0 = edge snap only."));
        gridSpinner.valueProperty().addListener((obs, o, n) -> {
            if (n != null) editSession.snapGridSize.set(n);
        });

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var headerBar = new HBox(6,
            sequenceNameLabel, spacer,
            durationLabel, durationSpinner,
            new Separator(), snapToggle, gridLabel, gridSpinner,
            new Separator(), undoButton, redoButton,
            new Separator(), saveButton
        );
        headerBar.setAlignment(Pos.CENTER_LEFT);
        headerBar.setPadding(new Insets(4, 8, 4, 8));

        // --- Overview scrubber ---
        var overviewHolder = new StackPane(overviewBar);
        overviewHolder.setPrefHeight(36);
        overviewHolder.setMinHeight(36);
        overviewHolder.setMaxHeight(36);

        // --- Track canvas ---
        var trackHolder = new StackPane(trackCanvas);
        VBox.setVgrow(trackHolder, Priority.ALWAYS);

        // --- Centre content ---
        var centre = new VBox(
            headerBar,
            new Separator(),
            overviewHolder,
            new Separator(),
            trackHolder
        );

        // --- Root layout ---
        var root = new BorderPane();
        root.setLeft(toolPalette);
        root.setCenter(centre);

        setPaneContent(root);

        // --- Keyboard shortcuts ---
        root.setOnKeyPressed(event -> {
            if (new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN).match(event)) {
                saveSequence();
                event.consume();
            } else if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN).match(event)) {
                editSession.redo();
                event.consume();
            } else if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN).match(event)) {
                editSession.undo();
                event.consume();
            } else if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                editSession.deleteSelectedClips();
                event.consume();
            } else if (new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN).match(event)) {
                editSession.duplicateSelectedClips();
                event.consume();
            } else if (new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN).match(event)) {
                // Select all clips
                editSession.selectedClipIds.clear();
                for (var clip : editSession.clips) editSession.selectedClipIds.add(clip.id());
                if (!editSession.clips.isEmpty())
                    editSession.primarySelectedClipId.set(editSession.clips.getFirst().id());
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                toolPalette.activeTool.set(SequenceToolKind.SELECT);
                editSession.clearSelection();
                event.consume();
            }
        });
        root.setFocusTraversable(true);

        // Return focus to the root when clicking the track canvas (away from spinners)
        trackCanvas.setOnMouseClicked(e -> root.requestFocus());
    }

    public void setOnTitleChanged(Runnable callback) { this.onTitleChanged = callback; }

    /** The display title for the BentoFX tab (includes dirty indicator). */
    public String tabTitle() {
        return sequenceName + (editSession.isDirty() ? " *" : "");
    }

    /** Load a sequence document into the editor. */
    public void open(SequenceDocument document) {
        editSession.open(document);
        sequenceName = document.name();
        sequenceNameLabel.setText("Sequence: " + document.name());
        // Expose edit session to inspector
        paneContext.session().activeEditSession.set(editSession);
        notifyTitleChanged();
    }

    private void notifyTitleChanged() {
        if (onTitleChanged != null) onTitleChanged.run();
    }

    /** Whether the editor has unsaved changes. */
    public boolean isDirty() {
        return editSession.isDirty();
    }

    /** Public save entry point for external callers (e.g. File→Save). */
    public void savePublic() { saveSequence(); }

    /** Save changes back to the project repository and persist to disk. */
    private void saveSequence() {
        var updated = editSession.toDocument();
        var repo = paneContext.session().project.repository.get();
        repo.removeSequence(updated.id());
        repo.addSequence(updated);
        editSession.open(updated);
        paneContext.session().activeEditSession.set(editSession);
        paneContext.session().project.explorer.refresh();
        notifyTitleChanged();
        // Persist to disk
        paneContext.controller().saveProject();
    }

    @Override
    public void dispose() {
        overviewBar.dispose();
        trackCanvas.dispose();
    }
}
