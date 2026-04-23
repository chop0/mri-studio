package ax.xz.mri.ui.workbench.pane.schematic;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

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
 */
public final class SchematicToolbar extends HBox {
    public SchematicToolbar(SchematicCanvas canvas) {
        getStyleClass().add("schematic-toolbar");
        setSpacing(4);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(4, 8, 4, 8));

        var modeGroup = new ToggleGroup();
        var select = modeButton(SchematicCanvas.PrimaryMode.SELECT, "Pointer \u2014 click components, drag to move.");
        var pan = modeButton(SchematicCanvas.PrimaryMode.PAN, "Hand \u2014 drag empty space to pan the view.");
        var wire = modeButton(SchematicCanvas.PrimaryMode.WIRE, "Wire \u2014 click a terminal to start wiring.");
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

        var zoomOut = toolButton("\u2212", "Zoom out (Ctrl-)");
        zoomOut.setOnAction(e -> canvas.zoomBy(1.0 / 1.2));
        var zoomIn = toolButton("+", "Zoom in (Ctrl+)");
        zoomIn.setOnAction(e -> canvas.zoomBy(1.2));
        var fit = toolButton("Fit", "Fit to view (Ctrl+F)");
        fit.setOnAction(e -> canvas.fitToView());
        var reset = toolButton("1:1", "Reset zoom (Ctrl+0)");
        reset.setOnAction(e -> canvas.resetZoom());

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var shortcuts = new javafx.scene.control.Label(
            "V Select  \u00b7  H Pan  \u00b7  W Wire  \u00b7  Del Remove  \u00b7  \u2318C/\u2318V Copy/Paste");
        shortcuts.getStyleClass().add("schematic-toolbar-hint");

        getChildren().addAll(
            select, pan, wire,
            new Separator(javafx.geometry.Orientation.VERTICAL),
            zoomOut, zoomIn, fit, reset,
            spacer,
            shortcuts
        );
    }

    private static ToggleButton modeButton(SchematicCanvas.PrimaryMode mode, String tooltip) {
        var button = new ToggleButton(mode.label());
        button.setUserData(mode);
        button.setTooltip(new Tooltip(tooltip + "  (" + mode.shortcut() + ")"));
        button.getStyleClass().add("schematic-toolbar-button");
        return button;
    }

    private static Button toolButton(String label, String tooltip) {
        var button = new Button(label);
        button.getStyleClass().add("schematic-toolbar-button");
        button.setTooltip(new Tooltip(tooltip));
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
