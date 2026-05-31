package ax.xz.mri.ui.inspector.editors;

import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import org.controlsfx.property.editor.PropertyEditor;

/**
 * PropertySheet editor for time-valued properties (start, duration). Backed
 * by a {@link Spinner} so the user gets keyboard increment/decrement, mouse
 * wheel adjustment, and free typing — all stock JavaFX behaviours.
 */
public final class DurationEditor implements PropertyEditor<Number> {
    private final Spinner<Double> spinner;

    public DurationEditor(double min, double max) {
        this.spinner = new Spinner<>();
        this.spinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(
            Math.min(0, min), Math.max(min + 1, max), min, 1.0));
        this.spinner.setEditable(true);
        this.spinner.getEditor().setPrefColumnCount(10);
        this.spinner.getStyleClass().add("duration-editor");
    }

    @Override public Node getEditor() { return spinner; }
    @Override public Number getValue() { return spinner.getValue(); }
    @Override public void setValue(Number value) {
        if (value == null) return;
        spinner.getValueFactory().setValue(value.doubleValue());
    }

    public ObservableValue<Double> valueProperty() { return spinner.valueProperty(); }
}
