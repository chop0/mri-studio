package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import ax.xz.mri.ui.viewmodel.SequenceSimulationSession;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import ax.xz.mri.ui.workbench.pane.timeline.TimelineCanvas;
import ax.xz.mri.ui.workbench.pane.timeline.TimelineOverviewBar;
import ax.xz.mri.util.SiFormat;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
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
 * Root pane for the clip-based sequence editor ("DAW").
 *
 * <h3>Layout</h3>
 * <pre>
 *   ┌──────────────────────────────────────────────────────────┐
 *   │ Toolbar (tools │ undo/redo │ zoom │ save)                │
 *   ├──────────────────────────────────────────────────────────┤
 *   │ Overview bar (clip spans + mini waveforms + window)      │
 *   ├──────────────────────────────────────────────────────────┤
 *   │                                                          │
 *   │ Timeline canvas (tracks + clips)                         │
 *   │                                                          │
 *   ├──────────────────────────────────────────────────────────┤
 *   │ Status strip (selection │ span │ dt │ cursor │ total)    │
 *   └──────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Everything here is pure composition: the pane owns the three view
 * components ({@link TimelineOverviewBar}, {@link TimelineCanvas},
 * {@link SequenceToolPalette}) and wires them to a single
 * {@link SequenceEditSession}. No rendering logic, no event handling beyond
 * keyboard shortcuts.
 */
public final class SequenceEditorPane extends WorkbenchPane {

    private final SequenceEditSession editSession = new SequenceEditSession();
    public SequenceEditSession editSession() { return editSession; }

    private final SequenceToolPalette toolPalette;
    private final TimelineOverviewBar overviewBar;
    private final TimelineCanvas trackCanvas;

    private SequenceSimulationSession simSession;
    private Runnable onTitleChanged;
    private String sequenceName = "";

    public SequenceEditorPane(PaneContext paneContext) {
        super(paneContext);
        setPaneTitle("Sequence Editor");

        // ── Collaborators ───────────────────────────────────────────────────
        toolPalette  = new SequenceToolPalette(editSession);
        overviewBar  = new TimelineOverviewBar(editSession, paneContext.session().viewport);
        trackCanvas  = new TimelineCanvas(editSession);
        trackCanvas.setViewport(paneContext.session().viewport);

        toolPalette.setOnActiveToolChanged(() ->
            trackCanvas.setActiveCreationKind(toolPalette.activeClipKind())
        );

        editSession.setRepositorySupplier(() -> paneContext.session().project.repository.get());
        editSession.revision.addListener((obs, o, n) -> notifyTitleChanged());

        // ── Layout ──────────────────────────────────────────────────────────
        var root = new BorderPane();
        root.getStyleClass().add("sequence-editor");
        root.setTop(buildToolbar());
        root.setCenter(buildCentre());
        root.setBottom(buildStatusStrip(paneContext));
        setPaneContent(root);

        // ── Keyboard shortcuts ──────────────────────────────────────────────
        wireKeyboardShortcuts(root);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Toolbar (tools, undo/redo, zoom, save)
    // ══════════════════════════════════════════════════════════════════════════

    private HBox buildToolbar() {
        var undoBtn = new Button("\u21A9");
        undoBtn.setTooltip(new Tooltip("Undo (\u2318Z)"));
        undoBtn.disableProperty().bind(editSession.canUndoProperty().not());
        undoBtn.setOnAction(e -> editSession.undo());
        undoBtn.setFocusTraversable(false);
        undoBtn.getStyleClass().add("seq-toolbar-button");

        var redoBtn = new Button("\u21AA");
        redoBtn.setTooltip(new Tooltip("Redo (\u2318\u21E7Z)"));
        redoBtn.disableProperty().bind(editSession.canRedoProperty().not());
        redoBtn.setOnAction(e -> editSession.redo());
        redoBtn.setFocusTraversable(false);
        redoBtn.getStyleClass().add("seq-toolbar-button");

        var zoomOutBtn = new Button("\u2212");
        zoomOutBtn.setTooltip(new Tooltip("Zoom out"));
        zoomOutBtn.setOnAction(e -> {
            double centre = (editSession.viewStart.get() + editSession.viewEnd.get()) / 2;
            editSession.zoomViewAround(centre, 1.25);
        });
        zoomOutBtn.setFocusTraversable(false);
        zoomOutBtn.getStyleClass().add("seq-toolbar-button");

        var zoomInBtn = new Button("+");
        zoomInBtn.setTooltip(new Tooltip("Zoom in"));
        zoomInBtn.setOnAction(e -> {
            double centre = (editSession.viewStart.get() + editSession.viewEnd.get()) / 2;
            editSession.zoomViewAround(centre, 0.8);
        });
        zoomInBtn.setFocusTraversable(false);
        zoomInBtn.getStyleClass().add("seq-toolbar-button");

        var fitBtn = new Button("Fit");
        fitBtn.setTooltip(new Tooltip("Zoom to fit"));
        fitBtn.setOnAction(e -> editSession.fitView());
        fitBtn.setFocusTraversable(false);
        fitBtn.getStyleClass().add("seq-toolbar-button");

        var saveBtn = new Button("Save");
        saveBtn.setTooltip(new Tooltip("Save (\u2318S)"));
        saveBtn.setOnAction(e -> saveSequence());
        saveBtn.setFocusTraversable(false);
        saveBtn.getStyleClass().addAll("seq-toolbar-button", "primary");

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var toolbar = new HBox(4,
            toolPalette,
            verticalSeparator(),
            undoBtn, redoBtn,
            verticalSeparator(),
            zoomOutBtn, zoomInBtn, fitBtn,
            spacer,
            saveBtn
        );
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(3, 6, 3, 4));
        toolbar.getStyleClass().add("seq-toolbar");
        return toolbar;
    }

    private Separator verticalSeparator() {
        var sep = new Separator(javafx.geometry.Orientation.VERTICAL);
        sep.setPadding(new Insets(2, 4, 2, 4));
        return sep;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Centre (overview + track canvas)
    // ══════════════════════════════════════════════════════════════════════════

    private VBox buildCentre() {
        var overviewHolder = new StackPane(overviewBar);
        overviewHolder.setMinHeight(40);
        overviewHolder.setPrefHeight(40);
        overviewHolder.setMaxHeight(40);
        overviewHolder.getStyleClass().add("seq-overview-holder");

        var canvasHolder = new StackPane(trackCanvas);
        canvasHolder.getStyleClass().add("seq-canvas-holder");
        VBox.setVgrow(canvasHolder, Priority.ALWAYS);

        return new VBox(overviewHolder, canvasHolder);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Status strip (selection + timing readouts)
    // ══════════════════════════════════════════════════════════════════════════

    private HBox buildStatusStrip(PaneContext paneContext) {
        var selection = new Label();
        selection.getStyleClass().add("seq-status-selection");
        Runnable updateSel = () -> {
            int n = editSession.selectedClipIds.size();
            selection.setText(n == 0 ? "No selection" : (n == 1 ? "1 clip selected" : n + " clips selected"));
        };
        updateSel.run();
        editSession.selectedClipIds.addListener((javafx.collections.SetChangeListener<String>) c -> updateSel.run());

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var strip = new HBox(10,
            selection,
            spacer,
            statItem("span",   viewSpanBinding()),
            statItem("dt",     dtBinding()),
            statItem("cursor", cursorBinding(paneContext)),
            statItem("total",  totalDurationBinding())
        );
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.setPadding(new Insets(3, 10, 3, 10));
        strip.getStyleClass().add("seq-status-strip");
        return strip;
    }

    private HBox statItem(String label, StringBinding value) {
        var lbl = new Label(label.toUpperCase());
        lbl.getStyleClass().add("seq-status-label");
        var val = new Label();
        val.getStyleClass().add("seq-status-value");
        val.textProperty().bind(value);
        var row = new HBox(4, lbl, val);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private StringBinding viewSpanBinding() {
        return Bindings.createStringBinding(
            () -> SiFormat.time(editSession.viewEnd.get() - editSession.viewStart.get()),
            editSession.viewStart, editSession.viewEnd);
    }

    private StringBinding dtBinding() {
        return Bindings.createStringBinding(() -> SiFormat.time(editSession.dt.get()), editSession.dt);
    }

    private StringBinding totalDurationBinding() {
        return Bindings.createStringBinding(
            () -> SiFormat.time(editSession.totalDuration.get()), editSession.totalDuration);
    }

    private StringBinding cursorBinding(PaneContext paneContext) {
        var viewport = paneContext.session().viewport;
        return Bindings.createStringBinding(() -> SiFormat.time(viewport.tC.get()), viewport.tC);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Keyboard shortcuts
    // ══════════════════════════════════════════════════════════════════════════

    private void wireKeyboardShortcuts(BorderPane root) {
        root.setOnKeyPressed(event -> {
            if (new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN).match(event)) {
                saveSequence(); event.consume();
            } else if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN).match(event)) {
                editSession.redo(); event.consume();
            } else if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN).match(event)) {
                editSession.undo(); event.consume();
            } else if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                editSession.deleteSelectedClips(); event.consume();
            } else if (new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN).match(event)) {
                editSession.duplicateSelectedClips(); event.consume();
            } else if (new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN).match(event)) {
                editSession.selectAll(); event.consume();
            } else if (new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN).match(event)) {
                editSession.fitView(); event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                toolPalette.activeTool.set(SequenceToolKind.SELECT);
                editSession.clearSelection();
                event.consume();
            }
        });
        root.setFocusTraversable(true);
        trackCanvas.setOnMouseClicked(e -> root.requestFocus());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // External API (matches the old pane)
    // ══════════════════════════════════════════════════════════════════════════

    /** Wire the simulation session (called by WorkbenchController after open). */
    public void wireSimSession(SequenceSimulationSession session) {
        this.simSession = session;
        editSession.applyActiveConfig(session.activeConfig.get());
        session.activeConfig.addListener((obs, o, n) -> editSession.applyActiveConfig(n));
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
        var currentConfigId = updated.activeSimConfigId();
        var repo = paneContext.session().project.repository.get();
        repo.removeSequence(updated.id());
        repo.addSequence(updated);
        editSession.open(updated);
        if (currentConfigId != null) editSession.setOriginalSimConfigId(currentConfigId);
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
