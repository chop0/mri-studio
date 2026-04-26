package ax.xz.mri.ui.wizard.starters;

import ax.xz.mri.ui.wizard.WizardStep;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/** Wizard step for configuring the low-field MRI template (B0 strength, gamma). */
final class LowFieldMriConfigStep implements WizardStep {
	private final Spinner<Double> b0Spinner;
	private final Spinner<Double> gammaSpinner;
	private final BooleanBinding valid = Bindings.createBooleanBinding(() -> true);
	private final VBox root;

	LowFieldMriConfigStep() {
		b0Spinner = dblSpinner(0.001, 20, 0.0154, 0.001);
		gammaSpinner = dblSpinner(1e6, 1e9, 267.522e6, 1e6);

		var header = new Label("Field parameters");
		header.getStyleClass().add("section-header");

		var desc = new Label("Configure the main magnetic field for this MRI system.");
		desc.setWrapText(true);
		desc.setStyle("-fx-text-fill: #64748b;");

		var grid = new GridPane();
		grid.setHgap(10);
		grid.setVgap(8);
		grid.addRow(0, new Label("B\u2080 field strength (T)"), b0Spinner);
		grid.addRow(1, new Label("\u03b3 gyromagnetic ratio (rad/s/T)"), gammaSpinner);

		root = new VBox(10, header, desc, grid);
		root.setPadding(new Insets(4));
	}

	@Override public String title() { return "Field Config"; }
	@Override public Node content() { return root; }
	@Override public BooleanBinding validProperty() { return valid; }

	double getB0Tesla() { return b0Spinner.getValue(); }
	double getGamma() { return gammaSpinner.getValue(); }

	private static Spinner<Double> dblSpinner(double min, double max, double value, double step) {
		var s = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, value, step));
		s.setEditable(true);
		s.setPrefWidth(150);
		s.focusedProperty().addListener((obs, o, f) -> {
			// Commit any in-flight text edit on blur; ignore parse failure.
			if (!f) try { s.increment(0); } catch (Exception ignored) {}
		});
		return s;
	}
}
