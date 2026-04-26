package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * Reusable inspector-row builders. Every presenter's {@code buildInspector}
 * calls these instead of wiring its own {@link TextField}s — gives the
 * inspector a uniform look and centralises parse-error handling.
 */
public final class InspectorFields {
    private InspectorFields() {}

    public static Node stringField(String label, String value, Consumer<String> onChange) {
        var row = rowWithLabel(label);
        var field = new TextField(value);
        field.setPrefWidth(130);
        field.focusedProperty().addListener((obs, o, focused) -> {
            if (!focused) onChange.accept(field.getText());
        });
        field.setOnAction(e -> onChange.accept(field.getText()));
        row.getChildren().add(field);
        return row;
    }

    public static Node doubleField(String label, double value, Consumer<Double> onChange) {
        var row = rowWithLabel(label);
        var field = new TextField(format(value));
        field.setPrefWidth(130);
        Runnable commit = () -> {
            try { onChange.accept(Double.parseDouble(field.getText().trim())); }
            // Reject malformed input by reverting to the last good value.
            catch (NumberFormatException ignored) { field.setText(format(value)); }
        };
        field.focusedProperty().addListener((obs, o, focused) -> { if (!focused) commit.run(); });
        field.setOnAction(e -> commit.run());
        row.getChildren().add(field);
        return row;
    }

    public static <T> Node enumField(String label, T[] values, T selected, Consumer<T> onChange) {
        var row = rowWithLabel(label);
        var combo = new ComboBox<T>(FXCollections.observableArrayList(List.of(values)));
        combo.setValue(selected);
        combo.setPrefWidth(130);
        combo.setOnAction(e -> onChange.accept(combo.getValue()));
        row.getChildren().add(combo);
        return row;
    }

    public static HBox rowWithLabel(String label) {
        var row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        var l = new Label(label);
        l.setPrefWidth(100);
        row.getChildren().add(l);
        return row;
    }

    public static String format(double value) {
        if (value == 0) return "0";
        double abs = Math.abs(value);
        if (abs >= 1e-3 && abs < 1e6) return String.format("%.6g", value);
        return String.format("%.3e", value);
    }
}
