package ax.xz.mri.ui.wizard;

import ax.xz.mri.model.substance.SubstanceStarter;
import ax.xz.mri.model.substance.SubstanceStarterLibrary;
import ax.xz.mri.project.SubstanceDocument;
import ax.xz.mri.ui.viewmodel.ProjectSessionViewModel;
import javafx.stage.Stage;

import java.util.Optional;

/** New-Substance wizard: pick a starter (continuous magnetisation / NV ensemble), then name it. */
public final class NewSubstanceWizard {
    private NewSubstanceWizard() {}

    public static Optional<SubstanceDocument> show(Stage owner, ProjectSessionViewModel project) {
        var starterStep = new ChoiceStep<>(
            "Starter", "Choose a template to seed the substance",
            SubstanceStarterLibrary.all(),
            SubstanceStarter::name,
            SubstanceStarter::description);

        var nameStep = new NameStep("Enter a name for the substance", "New Substance") {
            @Override
            public void onEnter() {
                var starter = starterStep.getValue();
                if (starter != null && (getValue().isBlank() || getValue().equals("New Substance"))) {
                    setValue(starter.name());
                }
                super.onEnter();
            }
        };

        return WizardDialog.<SubstanceDocument>builder("New Substance")
            .step(starterStep)
            .step(nameStep)
            .resultFactory(() -> project.createSubstance(
                nameStep.getValue(),
                starterStep.getValue().template()))
            .build(owner)
            .showAndWait();
    }
}
