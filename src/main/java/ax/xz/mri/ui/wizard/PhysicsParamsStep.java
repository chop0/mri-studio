package ax.xz.mri.ui.wizard;

import module ax.xz.mri;
import module javafx.base;
import module javafx.controls;
import module javafx.graphics;

/**
 * Wizard step for picking the simulator's integration step. Spatial layout
 * (extent + resolution) lives on the substance documents the circuit
 * references; tissue physics (T₁, T₂, γ) ditto — neither belongs here.
 */
public final class PhysicsParamsStep implements WizardStep {
	private final Spinner<Double> dtSpinner;
	private final BooleanBinding valid = Bindings.createBooleanBinding(() -> true);
	private final VBox root;

	public PhysicsParamsStep() {
		this(PhysicsParams.DEFAULTS);
	}

	public PhysicsParamsStep(PhysicsParams defaults) {
		dtSpinner = dblSpinner(0.001, 1000, defaults.dtSeconds() * 1e6, 0.1); // in μs
		dtSpinner.setPrefWidth(130);

		var integrationHeader = new Label("Integration");
		integrationHeader.getStyleClass().add("section-header");
		var integrationGrid = new GridPane();
		integrationGrid.setHgap(10);
		integrationGrid.setVgap(6);
		integrationGrid.addRow(0, new Label("Time step (μs)"), dtSpinner);

		var hint = new Label("Spatial layout — half-extent and grid resolution — lives on the "
			+ "substance the circuit references; edit it in the substance pane.");
		hint.setWrapText(true);
		hint.setStyle("-fx-text-fill: #5d6470; -fx-font-size: 11;");

		root = new VBox(10, integrationHeader, integrationGrid, hint);
		root.setPadding(new Insets(4));
	}

	@Override public String title() { return "Integration"; }
	@Override public Node content() { return root; }
	@Override public BooleanBinding validProperty() { return valid; }

	/** Open the dt spinner at the template's recommended step. */
	public void applyDefaults(PhysicsParams defaults) {
		if (dtSpinner.getValueFactory() != null)
			dtSpinner.getValueFactory().setValue(defaults.dtSeconds() * 1e6);
	}

	public PhysicsParams getValue() {
		return new PhysicsParams(dtSpinner.getValue() * 1e-6);
	}

	private static Spinner<Double> dblSpinner(double min, double max, double value, double step) {
		var s = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, value, step));
		s.setEditable(true);
		s.setPrefWidth(130);
		s.focusedProperty().addListener((obs, o, f) -> {
			// Commit any in-flight text edit on blur; ignore parse failure (revert to last good value).
			if (!f) try { s.increment(0); } catch (Exception ignored) {}
		});
		return s;
	}
}
