package ax.xz.mri.ui.workbench.pane;

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

    /** Tools that are functional in Phase 1. All others are disabled placeholders. */
    private static final Set<SequenceToolKind> ENABLED_TOOLS = Set.of(
        SequenceToolKind.INSERT_SEGMENT,
        SequenceToolKind.DELETE_SEGMENT,
        SequenceToolKind.DUPLICATE_SEGMENT
    );

    private final Map<SequenceToolKind, Button> buttons = new EnumMap<>(SequenceToolKind.class);

    public SequenceToolPalette(SequenceEditSession editSession) {
        setAlignment(Pos.TOP_CENTER);
        setPadding(new Insets(4));
        setSpacing(2);
        setPrefWidth(40);
        setMinWidth(40);
        setMaxWidth(40);
        getStyleClass().add("seq-tool-palette");

        // Drawing tools
        addTool(SequenceToolKind.DRAW);
        addTool(SequenceToolKind.RECTANGLE);
        addTool(SequenceToolKind.SINC);
        addTool(SequenceToolKind.TRAPEZOID);
        addTool(SequenceToolKind.GAUSSIAN);
        getChildren().add(separator());

        // Segment actions
        addActionTool(SequenceToolKind.INSERT_SEGMENT, editSession::insertSegmentAfterSelection);
        addActionTool(SequenceToolKind.DELETE_SEGMENT, editSession::removeSelectedSegment);
        addActionTool(SequenceToolKind.DUPLICATE_SEGMENT, editSession::duplicateSelectedSegment);
        getChildren().add(separator());

        // Composite blocks
        addTool(SequenceToolKind.SPOILER);
        addTool(SequenceToolKind.REFOCUS);
        addTool(SequenceToolKind.SLICE_SELECT);
        addTool(SequenceToolKind.READOUT);
        getChildren().add(separator());

        // Overlay toggles
        addTool(SequenceToolKind.CONSTRAINTS);

        // Highlight active tool
        activeTool.addListener((obs, oldTool, newTool) -> {
            if (oldTool != null && buttons.containsKey(oldTool)) {
                buttons.get(oldTool).getStyleClass().remove(STYLE_TOOL_ACTIVE);
            }
            if (newTool != null && buttons.containsKey(newTool)) {
                buttons.get(newTool).getStyleClass().add(STYLE_TOOL_ACTIVE);
            }
        });
    }

    /** Add a drawing/toggle tool (click to select as active tool). */
    private void addTool(SequenceToolKind kind) {
        var button = createButton(kind);
        boolean enabled = ENABLED_TOOLS.contains(kind);
        button.setDisable(!enabled);
        button.setOpacity(enabled ? 1.0 : 0.35);
        if (kind.isDrawingTool()) {
            button.setOnAction(event -> {
                activeTool.set(activeTool.get() == kind ? null : kind);
            });
        }
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
