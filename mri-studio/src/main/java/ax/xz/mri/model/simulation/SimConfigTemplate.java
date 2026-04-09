package ax.xz.mri.model.simulation;

import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.service.ObjectFactory;
import ax.xz.mri.ui.wizard.WizardStep;

import java.util.List;

/**
 * Named starting-point templates for new simulation configs.
 * Each template knows what fields it creates and what user-configurable
 * parameters it needs (exposed as an optional wizard step).
 */
public enum SimConfigTemplate {
	EMPTY("Empty", "No fields — build from scratch") {
		@Override
		public List<FieldDefinition> createFields(ProjectRepository repo) {
			return List.of();
		}

		@Override
		public WizardStep configStep() {
			return null; // no configuration needed
		}
	},
	LOW_FIELD_MRI("Standard low-field MRI", "B0 + Gx + Gz + RF for a ~15 mT Helmholtz system") {
		private LowFieldMriConfigStep step;

		@Override
		public List<FieldDefinition> createFields(ProjectRepository repo) {
			double b0Tesla = step != null ? step.getB0Tesla() : 0.0154;
			double gamma = step != null ? step.getGamma() : 267.522e6;

			var b0Eigen = ObjectFactory.findOrCreateEigenfield(repo,
				"B0 Helmholtz", "Main field from Helmholtz-like coils", EigenfieldPreset.BIOT_SAVART_HELMHOLTZ);
			var gxEigen = ObjectFactory.findOrCreateEigenfield(repo,
				"Gradient X (Golay)", "X-gradient from Golay coils", EigenfieldPreset.IDEAL_GRADIENT_X);
			var gzEigen = ObjectFactory.findOrCreateEigenfield(repo,
				"Gradient Z (Maxwell)", "Z-gradient from Maxwell pair", EigenfieldPreset.IDEAL_GRADIENT_Z);
			var rfEigen = ObjectFactory.findOrCreateEigenfield(repo,
				"RF Loop", "Transverse field from loop antenna", EigenfieldPreset.UNIFORM_B_PERP);

			double larmorHz = gamma * b0Tesla / (2 * Math.PI);

			return List.of(
				new FieldDefinition("B0", ControlType.BINARY, 0, b0Tesla, 0, b0Eigen.id()),
				new FieldDefinition("Gradient X", ControlType.LINEAR, -0.030, 0.030, 0, gxEigen.id()),
				new FieldDefinition("Gradient Z", ControlType.LINEAR, -0.030, 0.030, 0, gzEigen.id()),
				new FieldDefinition("RF", ControlType.LINEAR, 0, 200e-6, larmorHz, rfEigen.id())
			);
		}

		@Override
		public WizardStep configStep() {
			if (step == null) step = new LowFieldMriConfigStep();
			return step;
		}
	};

	private final String displayName;
	private final String description;

	SimConfigTemplate(String displayName, String description) {
		this.displayName = displayName;
		this.description = description;
	}

	public String displayName() { return displayName; }
	public String description() { return description; }

	/**
	 * Create the field definitions for this template.
	 * If {@link #configStep()} is non-null, call this AFTER the user has filled in the config step.
	 */
	public abstract List<FieldDefinition> createFields(ProjectRepository repo);

	/**
	 * An optional wizard step for configuring this template's parameters.
	 * Returns null if the template needs no user configuration.
	 */
	public abstract WizardStep configStep();

	@Override
	public String toString() { return displayName; }
}
