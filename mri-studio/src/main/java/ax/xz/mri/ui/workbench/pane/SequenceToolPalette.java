package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.sequence.ClipShape;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Horizontal icon-only tool bar for the sequence editor (along the top).
 * Compact layout suitable for a thin editor pane at the bottom of the window.
 */
public final class SequenceToolPalette extends HBox {
    private static final String STYLE_TOOL_BUTTON = "seq-tool-button";
    private static final String STYLE_TOOL_ACTIVE = "seq-tool-active";

    public final ObjectProperty<SequenceToolKind> activeTool = new SimpleObjectProperty<>(null);

    private static final Set<SequenceToolKind> DISABLED_TOOLS = Set.of(
        SequenceToolKind.SPOILER, SequenceToolKind.REFOCUS,
        SequenceToolKind.SLICE_SELECT, SequenceToolKind.READOUT,
        SequenceToolKind.CONSTRAINTS
    );

    private final Map<SequenceToolKind, Button> buttons = new EnumMap<>(SequenceToolKind.class);
    private Runnable onActiveToolChanged;

    public SequenceToolPalette(SequenceEditSession editSession) {
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(2, 4, 2, 4));
        setSpacing(2);
        getStyleClass().add("seq-tool-palette");

        // Select tool
        addCreationTool(SequenceToolKind.SELECT);
        getChildren().add(vsep());

        // Clip creation tools
        addCreationTool(SequenceToolKind.CONSTANT);
        addCreationTool(SequenceToolKind.SINC);
        addCreationTool(SequenceToolKind.TRAPEZOID);
        addCreationTool(SequenceToolKind.GAUSSIAN);
        addCreationTool(SequenceToolKind.SPLINE);
        addCreationTool(SequenceToolKind.TRIANGLE);
        getChildren().add(vsep());

        // Clip actions
        addActionTool(SequenceToolKind.DELETE_CLIP, editSession::deleteSelectedClips);
        addActionTool(SequenceToolKind.DUPLICATE_CLIP, editSession::duplicateSelectedClips);

        // Highlight active tool
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

    public void setOnActiveToolChanged(Runnable callback) { this.onActiveToolChanged = callback; }

    public ClipShape activeClipShape() {
        var tool = activeTool.get();
        return tool != null ? tool.clipShape() : null;
    }

    private void addCreationTool(SequenceToolKind kind) {
        var button = createButton(kind);
        button.setOnAction(event -> {
            if (activeTool.get() == kind) {
                activeTool.set(SequenceToolKind.SELECT);
            } else {
                activeTool.set(kind);
            }
        });
        buttons.put(kind, button);
        getChildren().add(button);
    }

    private void addActionTool(SequenceToolKind kind, Runnable action) {
        var button = createButton(kind);
        button.setOnAction(event -> action.run());
        buttons.put(kind, button);
        getChildren().add(button);
    }

    private Button createButton(SequenceToolKind kind) {
        var icon = SequenceEditorIcons.create(kind);
        var button = new Button();
        button.setGraphic(icon);
        button.getStyleClass().add(STYLE_TOOL_BUTTON);
        button.setTooltip(new Tooltip(kind.displayName() + "\n" + kind.description()));
        button.setPrefSize(28, 28);
        button.setMinSize(28, 28);
        button.setMaxSize(28, 28);
        button.setFocusTraversable(false);
        boolean disabled = DISABLED_TOOLS.contains(kind);
        button.setDisable(disabled);
        if (disabled) button.setOpacity(0.35);
        return button;
    }

    private Separator vsep() {
        var sep = new Separator(Orientation.VERTICAL);
        sep.setPadding(new Insets(0, 2, 0, 2));
        return sep;
    }
}
