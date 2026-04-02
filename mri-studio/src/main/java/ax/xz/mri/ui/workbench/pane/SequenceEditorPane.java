package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Clip-based sequence editor pane with horizontal tool bar, overview scrubber,
 * and multi-track canvas. Designed to be thin (bottom of window).
 */
public final class SequenceEditorPane extends WorkbenchPane {
    private final SequenceEditSession editSession = new SequenceEditSession();
    public SequenceEditSession editSession() { return editSession; }
    private final SequenceOverviewBar overviewBar;
    private final ClipTrackCanvas trackCanvas;
    private final SequenceToolPalette toolPalette;
    private Runnable onTitleChanged;
    private String sequenceName = "";

    // Simulation session (wired after construction)
    private ax.xz.mri.ui.viewmodel.SequenceSimulationSession simSession;

    public SequenceEditorPane(PaneContext paneContext) {
        super(paneContext);
        setPaneTitle("Sequence Editor");

        toolPalette = new SequenceToolPalette(editSession);
        overviewBar = new SequenceOverviewBar(editSession, paneContext.session().viewport);
        trackCanvas = new ClipTrackCanvas(editSession);
        trackCanvas.setViewport(paneContext.session().viewport);

        toolPalette.setOnActiveToolChanged(() ->
            trackCanvas.setActiveCreationShape(toolPalette.activeClipShape())
        );

        editSession.revision.addListener((obs, o, n) -> notifyTitleChanged());

        // --- Buttons ---
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

        var saveButton = new Button("\ud83d\udcbe");
        saveButton.setTooltip(new Tooltip("Save (\u2318S)"));
        saveButton.setOnAction(event -> saveSequence());
        saveButton.setFocusTraversable(false);

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // --- Toolbar: tools + undo/redo/save ---
        var toolbar = new HBox(3);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(2, 6, 2, 6));
        toolbar.getChildren().addAll(
            toolPalette,
            new Separator(javafx.geometry.Orientation.VERTICAL),
            undoButton, redoButton, saveButton
        );

        // --- Overview scrubber ---
        var overviewHolder = new StackPane(overviewBar);
        overviewHolder.setPrefHeight(28);
        overviewHolder.setMinHeight(28);
        overviewHolder.setMaxHeight(28);

        // --- Track canvas ---
        var trackHolder = new StackPane(trackCanvas);
        VBox.setVgrow(trackHolder, Priority.ALWAYS);

        var root = new VBox(toolbar, overviewHolder, trackHolder);
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
        trackCanvas.setOnMouseClicked(e -> root.requestFocus());
    }

    /** Wire the simulation session (called by WorkbenchController after open). */
    public void wireSimSession(ax.xz.mri.ui.viewmodel.SequenceSimulationSession session) {
        this.simSession = session;
        // Stale indicator on timeline title
        session.stale.addListener((obs, o, n) -> {
            if (paneContext.controller() != null) paneContext.controller().markTimelineStale(n);
        });
    }

    public void setOnTitleChanged(Runnable callback) { this.onTitleChanged = callback; }

    public String tabTitle() {
        return sequenceName + (editSession.isDirty() ? " *" : "");
    }

    public void open(SequenceDocument document) {
        editSession.open(document);
        sequenceName = document.name();
        paneContext.session().activeEditSession.set(editSession);
        notifyTitleChanged();
    }

    private void notifyTitleChanged() {
        if (onTitleChanged != null) onTitleChanged.run();
    }

    public boolean isDirty() { return editSession.isDirty(); }

    public void savePublic() { saveSequence(); }

    private void saveSequence() {
        var updated = editSession.toDocument();
        var repo = paneContext.session().project.repository.get();
        repo.removeSequence(updated.id());
        repo.addSequence(updated);
        editSession.open(updated);
        paneContext.session().activeEditSession.set(editSession);
        paneContext.session().project.explorer.refresh();
        notifyTitleChanged();
        paneContext.controller().saveProject();
    }

    @Override
    public void dispose() {
        overviewBar.dispose();
        trackCanvas.dispose();
    }
}
