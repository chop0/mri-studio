package ax.xz.mri.hardware.builtin.redpitaya.editor;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Shared row / section primitives so every sub-tab uses the same dense
 * layout and {@code cfg-*} CSS classes the rest of the studio uses.
 */
final class EditorRows {
    private EditorRows() {}

    static Node row(String label, Node control, String hint) {
        var l = new Label(label);
        l.getStyleClass().add("cfg-row-label");
        l.setMinWidth(140);
        var hintLabel = new Label(hint);
        hintLabel.getStyleClass().add("cfg-row-hint");
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var row = new HBox(8, l, control, spacer, hintLabel);
        row.getStyleClass().add("cfg-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    static void section(VBox into, String title, String description) {
        var t = new Label(title);
        t.getStyleClass().add("cfg-section-title");
        var d = new Label(description);
        d.getStyleClass().add("cfg-section-subtitle");
        d.setWrapText(true);
        into.getChildren().addAll(t, d);
    }

    static Node kv(String key, String value) {
        return kv(key, new Label(value));
    }

    static Node kv(String key, Label valueLabel) {
        var k = new Label(key);
        k.getStyleClass().add("cfg-kv-label");
        valueLabel.getStyleClass().add("cfg-kv-value");
        valueLabel.setWrapText(true);
        var row = new HBox(8, k, valueLabel);
        row.getStyleClass().add("cfg-kv");
        return row;
    }
}
