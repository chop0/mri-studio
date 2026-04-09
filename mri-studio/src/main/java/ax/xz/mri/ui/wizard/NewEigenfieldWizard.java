package ax.xz.mri.ui.wizard;

import ax.xz.mri.model.simulation.EigenfieldPreset;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.ui.viewmodel.ProjectSessionViewModel;
import javafx.stage.Stage;

import java.util.Arrays;
import java.util.Optional;

/** New Eigenfield wizard: Preset → Name. */
public final class NewEigenfieldWizard {
	private NewEigenfieldWizard() {}

	public static Optional<EigenfieldDocument> show(Stage owner, ProjectSessionViewModel project) {
		var presetStep = new ChoiceStep<>(
			"Preset", "Choose the eigenfield type",
			Arrays.asList(EigenfieldPreset.values()),
			EigenfieldPreset::displayName,
			EigenfieldPreset::description);
		var nameStep = new NameStep("Enter a name for the eigenfield", "New Eigenfield") {
			@Override
			public void onEnter() {
				// Pre-fill from selected preset
				var preset = presetStep.getValue();
				if (preset != null && (getValue().isBlank() || getValue().equals("New Eigenfield"))) {
					setValue(preset.displayName());
				}
				super.onEnter();
			}
		};

		return WizardDialog.<EigenfieldDocument>builder("New Eigenfield")
			.step(presetStep)
			.step(nameStep)
			.resultFactory(() -> {
				var preset = presetStep.getValue();
				return project.createEigenfield(nameStep.getValue(), preset.description(), preset);
			})
			.build(owner)
			.showAndWait();
	}
}
