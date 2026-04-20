package ax.xz.mri.ui.wizard;

import ax.xz.mri.service.ObjectFactory;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/** Wizard step for editing physics / spatial parameters. Always valid. */
public final class PhysicsParamsStep implements WizardStep {
	private final Spinner<Double> gammaSpinner;
	private final Spinner<Double> t1Spinner;
	private final Spinner<Double> t2Spinner;
	private final Spinner<Double> sliceSpinner;
	private final Spinner<Double> fovZSpinner;
	private final Spinner<Double> fovRSpinner;
	private final Spinner<Integer> nZSpinner;
	private final Spinner<Integer> nRSpinner;
	private final Spinner<Double> dtSpinner;
	private final BooleanBinding valid = Bindings.createBooleanBinding(() -> true);
	private final VBox root;

	public PhysicsParamsStep() {
		this(ObjectFactory.PhysicsParams.DEFAULTS);
	}

	public PhysicsParamsStep(ObjectFactory.PhysicsParams defaults) {
		gammaSpinner = dblSpinner(1e6, 1e9, defaults.gamma(), 1e6);
		t1Spinner = dblSpinner(0.1, 10000, defaults.t1Ms(), 10);
		t2Spinner = dblSpinner(0.1, 10000, defaults.t2Ms(), 10);
		sliceSpinner = dblSpinner(0.1, 100, defaults.sliceHalfMm(), 0.5);
		fovZSpinner = dblSpinner(0.1, 500, defaults.fovZMm(), 1);
		fovRSpinner = dblSpinner(0.1, 500, defaults.fovRMm(), 1);
		nZSpinner = intSpinner(2, 500, defaults.nZ());
		nRSpinner = intSpinner(2, 100, defaults.nR());
		dtSpinner = dblSpinner(0.01, 1000, defaults.dtSeconds() * 1e6, 0.1); // in μs
		dtSpinner.setPrefWidth(130);

		var header = new Label("Physics & spatial parameters");
		header.getStyleClass().add("section-header");

		var grid = new GridPane();
		grid.setHgap(10);
		grid.setVgap(6);
		int row = 0;
		grid.addRow(row++, new Label("\u03b3 (rad/s/T)"), gammaSpinner);
		grid.addRow(row++, new Label("T\u2081 (ms)"), t1Spinner);
		grid.addRow(row++, new Label("T\u2082 (ms)"), t2Spinner);
		grid.addRow(row++, new Label("Slice half (mm)"), sliceSpinner);
		grid.addRow(row++, new Label("FOV Z (mm)"), fovZSpinner);
		grid.addRow(row++, new Label("FOV R (mm)"), fovRSpinner);
		grid.addRow(row++, new Label("Grid Z"), nZSpinner);
		grid.addRow(row++, new Label("Grid R"), nRSpinner);
		grid.addRow(row, new Label("Time step (\u03bcs)"), dtSpinner);

		root = new VBox(10, header, grid);
		root.setPadding(new Insets(4));
	}

	@Override public String title() { return "Physics"; }
	@Override public Node content() { return root; }
	@Override public BooleanBinding validProperty() { return valid; }

	public ObjectFactory.PhysicsParams getValue() {
		return new ObjectFactory.PhysicsParams(
			gammaSpinner.getValue(),
			t1Spinner.getValue(), t2Spinner.getValue(),
			sliceSpinner.getValue(), fovZSpinner.getValue(), fovRSpinner.getValue(),
			nZSpinner.getValue(), nRSpinner.getValue(),
			dtSpinner.getValue() * 1e-6  // μs -> s
		);
	}

	private static Spinner<Double> dblSpinner(double min, double max, double value, double step) {
		var s = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, value, step));
		s.setEditable(true);
		s.setPrefWidth(130);
		s.focusedProperty().addListener((obs, o, f) -> {
			if (!f) try { s.increment(0); } catch (Exception ignored) {}
		});
		return s;
	}

	private static Spinner<Integer> intSpinner(int min, int max, int value) {
		var s = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, value));
		s.setEditable(true);
		s.setPrefWidth(80);
		s.focusedProperty().addListener((obs, o, f) -> {
			if (!f) try { s.increment(0); } catch (Exception ignored) {}
		});
		return s;
	}
}
