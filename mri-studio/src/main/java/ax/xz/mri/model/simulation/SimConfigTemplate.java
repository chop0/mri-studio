package ax.xz.mri.model.simulation;

import ax.xz.mri.model.simulation.dsl.EigenfieldStarterLibrary;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.service.ObjectFactory;
import ax.xz.mri.ui.wizard.WizardStep;

import java.util.List;

/**
 * Named starting-point templates for new simulation configs.
 *
 * <p>Each template creates a set of {@link FieldDefinition}s backed by
 * starter eigenfields in the starter library. Field amplitudes and carrier
 * frequencies are set for a typical low-field MRI setup when applicable.
 */
public enum SimConfigTemplate {
	EMPTY("Empty", "No fields — build from scratch") {
		@Override
		public List<FieldDefinition> createFields(ProjectRepository repo) {
			return List.of();
		}

		@Override
		public double referenceB0Tesla() { return 1.5; }

		@Override
		public WizardStep configStep() { return null; }
	},
	LOW_FIELD_MRI("Standard low-field MRI", "B0 + Gx + Gz + RF for a ~15 mT Helmholtz system") {
		private LowFieldMriConfigStep step;

		@Override
		public List<FieldDefinition> createFields(ProjectRepository repo) {
			double b0Tesla = step != null ? step.getB0Tesla() : 0.0154;
			double gamma = step != null ? step.getGamma() : 267.522e6;

			var b0Starter = EigenfieldStarterLibrary.byId("helmholtz-b0").orElseThrow();
			var gxStarter = EigenfieldStarterLibrary.byId("gradient-x").orElseThrow();
			var gzStarter = EigenfieldStarterLibrary.byId("gradient-z").orElseThrow();
			var rfStarter = EigenfieldStarterLibrary.byId("uniform-b-perp").orElseThrow();

			var b0 = ObjectFactory.findOrCreateEigenfield(repo, "B0 Helmholtz", b0Starter.description(), b0Starter.source());
			var gx = ObjectFactory.findOrCreateEigenfield(repo, "Gradient X", gxStarter.description(), gxStarter.source());
			var gz = ObjectFactory.findOrCreateEigenfield(repo, "Gradient Z", gzStarter.description(), gzStarter.source());
			var rf = ObjectFactory.findOrCreateEigenfield(repo, "RF Transverse", rfStarter.description(), rfStarter.source());

			double larmorHz = gamma * b0Tesla / (2 * Math.PI);

			// Channel order matches the legacy layout [rf_I, rf_Q, gx, gz] so existing
			// imported sequences compose without reshuffling. STATIC fields consume no
			// pulse-sequence channels so B0 can appear anywhere in the list.
			return List.of(
				new FieldDefinition("B0", b0.id(), AmplitudeKind.STATIC, 0, 0, b0Tesla),
				new FieldDefinition("RF", rf.id(), AmplitudeKind.QUADRATURE, larmorHz, 0, 200e-6),
				new FieldDefinition("Gradient X", gx.id(), AmplitudeKind.REAL, 0, -0.030, 0.030),
				new FieldDefinition("Gradient Z", gz.id(), AmplitudeKind.REAL, 0, -0.030, 0.030)
			);
		}

		@Override
		public double referenceB0Tesla() {
			return step != null ? step.getB0Tesla() : 0.0154;
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

	public abstract List<FieldDefinition> createFields(ProjectRepository repo);

	/** Reference B0 for the rotating frame that this template implies. */
	public abstract double referenceB0Tesla();

	public abstract WizardStep configStep();

	@Override
	public String toString() { return displayName; }
}
