package ax.xz.mri.ui.wizard;

import ax.xz.mri.model.procedure.ProcedureStarter;
import ax.xz.mri.model.procedure.ProcedureStarterLibrary;
import ax.xz.mri.project.ProcedureDocument;
import ax.xz.mri.ui.viewmodel.ProjectSessionViewModel;
import javafx.stage.Stage;

import java.util.Optional;

/** New-Procedure wizard: pick a starter template, then name it. */
public final class NewProcedureWizard {
    private NewProcedureWizard() {}

    public static Optional<ProcedureDocument> show(Stage owner, ProjectSessionViewModel project) {
        return buildDialog(owner, project).showAndWait();
    }

    /**
     * Test-only: build the wizard dialog (with both steps + a result factory)
     * without showing it. Previews use this to snapshot each step in turn
     * without blocking on the modal {@code showAndWait}. Production callers
     * should always use {@link #show}.
     */
    public static WizardDialog<ProcedureDocument> buildDialog(Stage owner, ProjectSessionViewModel project) {
        var starterStep = new ChoiceStep<>(
            "Starter", "Choose a starter to seed the procedure",
            ProcedureStarterLibrary.all(),
            ProcedureStarter::name,
            ProcedureStarter::description);

        var nameStep = new NameStep("Enter a name for the procedure", "New Procedure") {
            @Override
            public void onEnter() {
                var starter = starterStep.getValue();
                if (starter != null && (getValue().isBlank() || getValue().equals("New Procedure"))) {
                    setValue(starter.name());
                }
                super.onEnter();
            }
        };

        return WizardDialog.<ProcedureDocument>builder("New Procedure")
            .step(starterStep)
            .step(nameStep)
            .resultFactory(() -> {
                var starter = starterStep.getValue();
                return project.createProcedure(
                    nameStep.getValue(),
                    starter.source());
            })
            .build(owner);
    }
}
