package ax.xz.mri.ui.inspector.editors;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.controlsfx.property.editor.PropertyEditor;

/**
 * PropertySheet editor for clip amplitude. Composes a {@link Spinner} with
 * a units label so a 0.5 amp on a B1-routed clip reads as "0.5 V" or
 * "0.5 V/A·T" depending on the eigenfield's units.
 */
public final class AmplitudeEditor implements PropertyEditor<Number> {
    private final Spinner<Double> spinner = new Spinner<>();
    private final HBox layout;

    public AmplitudeEditor(String units) {
        spinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(
            -1e9, 1e9, 0, 0.1));
        spinner.setEditable(true);
        spinner.getEditor().setPrefColumnCount(8);
        spinner.getStyleClass().add("amplitude-editor");

        var unitsLabel = new Label(units == null ? "" : units);
        unitsLabel.getStyleClass().add("amplitude-editor-units");

        layout = new HBox(4, spinner, unitsLabel);
        layout.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(spinner, Priority.ALWAYS);
    }

    @Override public Node getEditor() { return layout; }
    @Override public Number getValue() { return spinner.getValue(); }
    @Override public void setValue(Number value) {
        if (value == null) return;
        spinner.getValueFactory().setValue(value.doubleValue());
    }
}
