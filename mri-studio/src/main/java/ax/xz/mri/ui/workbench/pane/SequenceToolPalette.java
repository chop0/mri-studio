package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.sequence.ClipShape;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** Vertical icon-only tool palette for the sequence editor (left sidebar). */
public final class SequenceToolPalette extends VBox {
    private static final String STYLE_TOOL_BUTTON = "seq-tool-button";
    private static final String STYLE_TOOL_ACTIVE = "seq-tool-active";

    public final ObjectProperty<SequenceToolKind> activeTool = new SimpleObjectProperty<>(null);

    /** Tools that are functional now. Composite blocks remain disabled. */
    private static final Set<SequenceToolKind> DISABLED_TOOLS = Set.of(
        SequenceToolKind.SPOILER,
        SequenceToolKind.REFOCUS,
        SequenceToolKind.SLICE_SELECT,
        SequenceToolKind.READOUT,
        SequenceToolKind.CONSTRAINTS
    );

    private final Map<SequenceToolKind, Button> buttons = new EnumMap<>(SequenceToolKind.class);
    private final SequenceEditSession editSession;

    /** Callback to notify the canvas when the active creation tool changes. */
    private Runnable onActiveToolChanged;

    public SequenceToolPalette(SequenceEditSession editSession) {
        this.editSession = editSession;
        setAlignment(Pos.TOP_CENTER);
        setPadding(new Insets(4));
        setSpacing(2);
        setPrefWidth(40);
        setMinWidth(40);
        setMaxWidth(40);
        getStyleClass().add("seq-tool-palette");

        // Select tool (default active)
        addCreationTool(SequenceToolKind.SELECT);
        getChildren().add(separator());

        // Clip creation tools
        addCreationTool(SequenceToolKind.CONSTANT);
        addCreationTool(SequenceToolKind.SINC);
        addCreationTool(SequenceToolKind.TRAPEZOID);
        addCreationTool(SequenceToolKind.GAUSSIAN);
        addCreationTool(SequenceToolKind.SPLINE);
        addCreationTool(SequenceToolKind.TRIANGLE);
        getChildren().add(separator());

        // Clip actions
        addActionTool(SequenceToolKind.DELETE_CLIP, editSession::deleteSelectedClips);
        addActionTool(SequenceToolKind.DUPLICATE_CLIP, editSession::duplicateSelectedClips);
        getChildren().add(separator());

        // Composite blocks (Phase 3+ — disabled)
        addDisabledTool(SequenceToolKind.SPOILER);
        addDisabledTool(SequenceToolKind.REFOCUS);
        addDisabledTool(SequenceToolKind.SLICE_SELECT);
        addDisabledTool(SequenceToolKind.READOUT);
        getChildren().add(separator());

        // Overlay toggles (disabled)
        addDisabledTool(SequenceToolKind.CONSTRAINTS);

        // Highlight active tool with visible pressed/selected styling
        activeTool.addListener((obs, oldTool, newTool) -> {
            if (oldTool != null && buttons.containsKey(oldTool)) {
                var btn = buttons.get(oldTool);
                btn.getStyleClass().remove(STYLE_TOOL_ACTIVE);
                btn.setStyle("");
            }
            if (newTool != null && buttons.containsKey(newTool)) {
                var btn = buttons.get(newTool);
                btn.getStyleClass().add(STYLE_TOOL_ACTIVE);
                btn.setStyle("-fx-background-color: #cde4f7; -fx-border-color: #90c0e8; -fx-border-radius: 3; -fx-background-radius: 3;");
            }
            if (onActiveToolChanged != null) onActiveToolChanged.run();
        });

        // Default to select tool
        activeTool.set(SequenceToolKind.SELECT);
    }

    public void setOnActiveToolChanged(Runnable callback) {
        this.onActiveToolChanged = callback;
    }

    /** Get the ClipShape for the currently active creation tool, or null. */
    public ClipShape activeClipShape() {
        var tool = activeTool.get();
        return tool != null ? tool.clipShape() : null;
    }

    /** Add a tool (toggle select; deselecting a creation tool falls back to SELECT). */
    private void addCreationTool(SequenceToolKind kind) {
        var button = createButton(kind);
        button.setOnAction(event -> {
            if (activeTool.get() == kind) {
                // Deselecting a creation tool → fall back to SELECT
                activeTool.set(SequenceToolKind.SELECT);
            } else {
                activeTool.set(kind);
            }
        });
        buttons.put(kind, button);
        getChildren().add(button);
    }

    /** Add an action tool (click to execute immediately). */
    private void addActionTool(SequenceToolKind kind, Runnable action) {
        var button = createButton(kind);
        button.setOnAction(event -> action.run());
        buttons.put(kind, button);
        getChildren().add(button);
    }

    /** Add a disabled placeholder tool. */
    private void addDisabledTool(SequenceToolKind kind) {
        var button = createButton(kind);
        button.setDisable(true);
        button.setOpacity(0.35);
        buttons.put(kind, button);
        getChildren().add(button);
    }

    private Button createButton(SequenceToolKind kind) {
        var icon = SequenceEditorIcons.create(kind);
        var button = new Button();
        button.setGraphic(icon);
        button.getStyleClass().add(STYLE_TOOL_BUTTON);
        button.setTooltip(new Tooltip(kind.displayName() + "\n" + kind.description()));
        button.setPrefSize(32, 32);
        button.setMinSize(32, 32);
        button.setMaxSize(32, 32);
        button.setFocusTraversable(false);
        return button;
    }

    private Separator separator() {
        var sep = new Separator();
        sep.setPadding(new Insets(4, 0, 4, 0));
        return sep;
    }
}
