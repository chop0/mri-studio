package ax.xz.mri.ui.wizard;

import ax.xz.mri.model.simulation.SimConfigTemplate;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.service.ObjectFactory;
import ax.xz.mri.ui.viewmodel.ProjectSessionViewModel;
import javafx.stage.Stage;

import java.util.Arrays;
import java.util.Optional;

/**
 * New Simulation Config wizard.
 *
 * <p>Steps: Name → Template → [Template Config (if template has one)] → Physics.
 * The template config step is dynamically included based on the selected template.
 */
public final class NewSimConfigWizard {
	private NewSimConfigWizard() {}

	public static Optional<SimulationConfigDocument> show(Stage owner, ProjectSessionViewModel project) {
		var nameStep = new NameStep("Enter a name for the simulation config", "New Config");

		var templateStep = new ChoiceStep<>(
			"Template", "Choose a starting template",
			Arrays.asList(SimConfigTemplate.values()),
			SimConfigTemplate::displayName,
			SimConfigTemplate::description);

		var physicsStep = new PhysicsParamsStep();

		// Build the wizard with all possible steps.
		// The template config step is a delegating wrapper that shows the selected
		// template's config step, or a "no configuration needed" placeholder.
		var templateConfigStep = new DelegatingTemplateStep(templateStep);

		var builder = WizardDialog.<SimulationConfigDocument>builder("New Simulation Config")
			.step(nameStep)
			.step(templateStep)
			.step(templateConfigStep)
			.step(physicsStep)
			.resultFactory(() -> {
				var template = templateStep.getValue();
				var params = physicsStep.getValue();
				return project.createSimConfig(nameStep.getValue(), template, params);
			});

		return builder.build(owner).showAndWait();
	}

	/**
	 * A step that delegates to the selected template's configStep().
	 * If the template has no config step, shows a simple "no configuration needed" message.
	 */
	private static final class DelegatingTemplateStep implements WizardStep {
		private final ChoiceStep<SimConfigTemplate> templateStep;
		private final javafx.scene.layout.StackPane container = new javafx.scene.layout.StackPane();
		private final javafx.beans.binding.BooleanBinding valid;
		private final javafx.scene.control.Label placeholder;

		DelegatingTemplateStep(ChoiceStep<SimConfigTemplate> templateStep) {
			this.templateStep = templateStep;
			placeholder = new javafx.scene.control.Label("No additional configuration needed for this template.");
			placeholder.setStyle("-fx-text-fill: #64748b;");
			container.getChildren().add(placeholder);
			container.setPadding(new javafx.geometry.Insets(20));
			valid = javafx.beans.binding.Bindings.createBooleanBinding(() -> {
				var template = templateStep.getValue();
				if (template == null) return true;
				var step = template.configStep();
				return step == null || step.validProperty().get();
			});
		}

		@Override
		public String title() { return "Configure"; }

		@Override
		public javafx.scene.Node content() { return container; }

		@Override
		public javafx.beans.binding.BooleanBinding validProperty() { return valid; }

		@Override
		public void onEnter() {
			container.getChildren().clear();
			var template = templateStep.getValue();
			if (template != null && template.configStep() != null) {
				var step = template.configStep();
				container.getChildren().add(step.content());
				step.onEnter();
			} else {
				container.getChildren().add(placeholder);
			}
		}
	}
}
