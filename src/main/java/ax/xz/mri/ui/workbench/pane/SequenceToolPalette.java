package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.sequence.ClipKind;
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
 * Compact icon-only tool bar for the sequence editor.
 *
 * <p>Exposes {@link #activeTool} as an observable property — the root pane
 * listens and pushes the associated {@link ClipKind} into the canvas's
 * creation-tool slot. Active-state styling is purely CSS (via the
 * {@code .seq-tool-active} class); no inline styles.
 */
public final class SequenceToolPalette extends HBox {
    public final ObjectProperty<SequenceToolKind> activeTool = new SimpleObjectProperty<>(null);

    private static final Set<SequenceToolKind> DISABLED_TOOLS = Set.of(
        SequenceToolKind.SPOILER,
        SequenceToolKind.REFOCUS,
        SequenceToolKind.SLICE_SELECT,
        SequenceToolKind.READOUT,
        SequenceToolKind.CONSTRAINTS
    );

    private final Map<SequenceToolKind, Button> buttons = new EnumMap<>(SequenceToolKind.class);
    private Runnable onActiveToolChanged;

    public SequenceToolPalette(SequenceEditSession editSession) {
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(2, 4, 2, 4));
        setSpacing(2);
        getStyleClass().add("seq-tool-palette");

        // Pointer
        addToggleTool(SequenceToolKind.SELECT);
        getChildren().add(verticalSeparator());

        // Clip creators — iterate in palette order
        addToggleTool(SequenceToolKind.CONSTANT);
        addToggleTool(SequenceToolKind.SINC);
        addToggleTool(SequenceToolKind.TRAPEZOID);
        addToggleTool(SequenceToolKind.GAUSSIAN);
        addToggleTool(SequenceToolKind.SPLINE);
        addToggleTool(SequenceToolKind.TRIANGLE);
        addToggleTool(SequenceToolKind.SINE);
        getChildren().add(verticalSeparator());

        // Immediate-action tools
        addActionTool(SequenceToolKind.DELETE_CLIP, editSession::deleteSelectedClips);
        addActionTool(SequenceToolKind.DUPLICATE_CLIP, editSession::duplicateSelectedClips);

        // CSS-driven active-state styling
        activeTool.addListener((obs, oldTool, newTool) -> {
            if (oldTool != null) {
                var b = buttons.get(oldTool);
                if (b != null) b.getStyleClass().remove("seq-tool-active");
            }
            if (newTool != null) {
                var b = buttons.get(newTool);
                if (b != null && !b.getStyleClass().contains("seq-tool-active")) {
                    b.getStyleClass().add("seq-tool-active");
                }
            }
            if (onActiveToolChanged != null) onActiveToolChanged.run();
        });

        activeTool.set(SequenceToolKind.SELECT);
    }

    public void setOnActiveToolChanged(Runnable callback) { this.onActiveToolChanged = callback; }

    /** The {@link ClipKind} of the current tool, or {@code null} for non-creation tools. */
    public ClipKind activeClipKind() {
        var tool = activeTool.get();
        return tool != null ? tool.clipKind() : null;
    }

    private void addToggleTool(SequenceToolKind kind) {
        var button = createButton(kind);
        button.setOnAction(event ->
            activeTool.set(activeTool.get() == kind ? SequenceToolKind.SELECT : kind));
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
        var button = new Button();
        button.setGraphic(SequenceEditorIcons.create(kind));
        button.getStyleClass().add("seq-tool-button");
        button.setTooltip(new Tooltip(kind.displayName() + "\n" + kind.description()));
        button.setPrefSize(28, 28);
        button.setMinSize(28, 28);
        button.setMaxSize(28, 28);
        button.setFocusTraversable(false);
        if (DISABLED_TOOLS.contains(kind)) {
            button.setDisable(true);
            button.getStyleClass().add("seq-tool-disabled");
        }
        return button;
    }

    private Separator verticalSeparator() {
        var sep = new Separator(Orientation.VERTICAL);
        sep.setPadding(new Insets(0, 2, 0, 2));
        return sep;
    }
}
