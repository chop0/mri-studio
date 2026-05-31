package ax.xz.mri.ui.workbench.pane.schematic;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/**
 * Compact tool palette above the schematic canvas.
 *
 * <p>Three primary modes — {@code Select}, {@code Pan}, {@code Wire} — are
 * exposed as toggle buttons bound to {@link SchematicCanvas#primaryModeProperty()}.
 * Zoom and fit-to-view controls sit next to them; they delegate to the canvas.
 *
 * <p>Keyboard equivalents (V / H / W for modes, {@code Ctrl+=} / {@code Ctrl+-}
 * / {@code Ctrl+0} / {@code Ctrl+F}) are handled inside the canvas itself via
 * {@link SchematicCanvas#handleKey}; this bar is the discoverable surface.
 *
 * <p>Every button is pinned at {@code USE_PREF_SIZE} so the toolbar never
 * truncates labels. The shortcut cheat-sheet lives in a {@code ?}-icon tooltip
 * rather than an inline label that would compete with the buttons for space.
 */
public final class SchematicToolbar extends HBox {

    private static final String SHORTCUT_HINT =
        "V / H / W   modes\n" +
        "Del   remove\n" +
        "Ctrl+C / V / D   copy / paste / duplicate\n" +
        "Ctrl+R / E   rotate / mirror\n" +
        "Ctrl+Z / Shift+Z   undo / redo\n" +
        "Ctrl++ / -   zoom in / out\n" +
        "Ctrl+0   reset zoom\n" +
        "Ctrl+F   fit to view";

    public SchematicToolbar(SchematicCanvas canvas) {
        getStyleClass().add("schematic-toolbar");
        setSpacing(4);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(4, 8, 4, 8));
        // Don't let parents narrow this row below its preferred width — that's
        // what truncated the buttons to "Se…" / "U…".
        setMinWidth(USE_PREF_SIZE);

        var modeGroup = new ToggleGroup();
        var select = modeButton(SchematicCanvas.PrimaryMode.SELECT, "Pointer — click components, drag to move.");
        var pan = modeButton(SchematicCanvas.PrimaryMode.PAN, "Hand — drag empty space to pan the view.");
        var wire = modeButton(SchematicCanvas.PrimaryMode.WIRE, "Wire — click a terminal to start wiring.");
        select.setToggleGroup(modeGroup);
        pan.setToggleGroup(modeGroup);
        wire.setToggleGroup(modeGroup);
        modeGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n == null) {
                modeGroup.selectToggle(o);
                return;
            }
            var mode = (SchematicCanvas.PrimaryMode) n.getUserData();
            canvas.setPrimaryMode(mode);
        });
        canvas.primaryModeProperty().addListener((obs, o, n) -> syncToggle(modeGroup, n));
        syncToggle(modeGroup, canvas.primaryMode());

        var undo = toolButton("Undo", "Undo (Ctrl+Z)");
        undo.setOnAction(e -> canvas.undo());
        var redo = toolButton("Redo", "Redo (Ctrl+Shift+Z)");
        redo.setOnAction(e -> canvas.redo());

        var zoomOut = toolButton("−", "Zoom out (Ctrl+-)");
        zoomOut.setOnAction(e -> canvas.zoomBy(1.0 / 1.2));
        var zoomIn = toolButton("+", "Zoom in (Ctrl++)");
        zoomIn.setOnAction(e -> canvas.zoomBy(1.2));
        var fit = toolButton("Fit", "Fit to view (Ctrl+F)");
        fit.setOnAction(e -> canvas.fitToView());
        var reset = toolButton("1:1", "Reset zoom (Ctrl+0)");
        reset.setOnAction(e -> canvas.resetZoom());

        var help = new Button("?");
        help.getStyleClass().add("schematic-toolbar-button");
        help.setMinWidth(USE_PREF_SIZE);
        help.setFocusTraversable(false);
        var helpTooltip = new Tooltip(SHORTCUT_HINT);
        helpTooltip.setShowDelay(Duration.millis(150));
        help.setTooltip(helpTooltip);

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        spacer.setMinWidth(0);

        getChildren().addAll(
            select, pan, wire,
            new Separator(Orientation.VERTICAL),
            undo, redo,
            new Separator(Orientation.VERTICAL),
            zoomOut, zoomIn, fit, reset,
            spacer,
            help
        );
    }

    private static ToggleButton modeButton(SchematicCanvas.PrimaryMode mode, String tooltip) {
        var button = new ToggleButton(mode.label());
        button.setUserData(mode);
        button.setTooltip(new Tooltip(tooltip + "  (" + mode.shortcut() + ")"));
        button.getStyleClass().add("schematic-toolbar-button");
        button.setMinWidth(USE_PREF_SIZE);
        return button;
    }

    private static Button toolButton(String label, String tooltip) {
        var button = new Button(label);
        button.getStyleClass().add("schematic-toolbar-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setMinWidth(USE_PREF_SIZE);
        return button;
    }

    private static void syncToggle(ToggleGroup group, SchematicCanvas.PrimaryMode mode) {
        for (var toggle : group.getToggles()) {
            if (toggle.getUserData() == mode && !toggle.isSelected()) {
                toggle.setSelected(true);
                return;
            }
        }
    }
}
