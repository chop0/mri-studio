package ax.xz.mri.ui.wizard;

import ax.xz.mri.model.simulation.dsl.EigenfieldStarter;
import ax.xz.mri.model.simulation.dsl.EigenfieldStarterLibrary;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.ui.viewmodel.ProjectSessionViewModel;
import javafx.stage.Stage;

import java.util.Optional;

/** New-Eigenfield wizard: pick a starter template, then name it. */
public final class NewEigenfieldWizard {
	private NewEigenfieldWizard() {}

	public static Optional<EigenfieldDocument> show(Stage owner, ProjectSessionViewModel project) {
		var starterStep = new ChoiceStep<>(
			"Starter", "Choose a template to seed the script",
			EigenfieldStarterLibrary.all(),
			EigenfieldStarter::name,
			EigenfieldStarter::description);

		var nameStep = new NameStep("Enter a name for the eigenfield", "New Eigenfield") {
			@Override
			public void onEnter() {
				var starter = starterStep.getValue();
				if (starter != null && (getValue().isBlank() || getValue().equals("New Eigenfield"))) {
					setValue(starter.name());
				}
				super.onEnter();
			}
		};

		return WizardDialog.<EigenfieldDocument>builder("New Eigenfield")
			.step(starterStep)
			.step(nameStep)
			.resultFactory(() -> {
				var starter = starterStep.getValue();
				return project.createEigenfield(
					nameStep.getValue(),
					starter.description(),
					starter.source(),
					starter.units());
			})
			.build(owner)
			.showAndWait();
	}
}
