package ax.xz.mri.model.simulation;

import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.service.ObjectFactory;

import java.util.List;

/**
 * Named starting-point templates for new simulation configs.
 * Offered as choices in the File → New → Simulation Config wizard.
 */
public enum SimConfigTemplate {
	EMPTY("Empty", "No fields — build from scratch") {
		@Override
		public List<FieldDefinition> createFields(double b0Tesla, double gamma, ProjectRepository repo) {
			return List.of();
		}
	},
	LOW_FIELD_MRI("Standard low-field MRI", "B0 + Gx + Gz + RF for a ~15 mT Helmholtz system") {
		@Override
		public List<FieldDefinition> createFields(double b0Tesla, double gamma, ProjectRepository repo) {
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
	};

	private final String displayName;
	private final String description;

	SimConfigTemplate(String displayName, String description) {
		this.displayName = displayName;
		this.description = description;
	}

	public String displayName() { return displayName; }
	public String description() { return description; }

	/** Create the field definitions for this template, creating eigenfields in the repo as needed. */
	public abstract List<FieldDefinition> createFields(double b0Tesla, double gamma, ProjectRepository repo);

	@Override
	public String toString() { return displayName; }
}
