package ax.xz.mri.ui.wizard;

import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.ui.viewmodel.ProjectSessionViewModel;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.util.Optional;

/**
 * New Sequence wizard.
 *
 * <p>Asks for a name and, if multiple simulation configs exist, which one to
 * bind the sequence to. A sequence without a config has no channel layout to
 * edit, so we always produce one pre-bound to a config. Creating a sequence
 * before any config exists is rejected up front.
 */
public final class NewSequenceWizard {
    private NewSequenceWizard() {}

    public static Optional<SequenceDocument> show(Stage owner, ProjectSessionViewModel project) {
        var repo = project.repository.get();
        var configs = repo.simConfigIds().stream()
            .map(id -> (SimulationConfigDocument) repo.node(id))
            .filter(java.util.Objects::nonNull)
            .toList();

        if (configs.isEmpty()) {
            var alert = new Alert(Alert.AlertType.INFORMATION,
                "Create a simulation config first — sequences are edited against a config's channel layout.");
            alert.setTitle("No simulation configs");
            alert.setHeaderText("A simulation config is required");
            alert.initOwner(owner);
            alert.showAndWait();
            return Optional.empty();
        }

        var nameStep = new NameStep("Enter a name for the sequence", "New Sequence");

        // Skip the picker if there's only one config — auto-bind it.
        if (configs.size() == 1) {
            var only = configs.getFirst();
            return WizardDialog.<SequenceDocument>builder("New Sequence")
                .step(nameStep)
                .resultFactory(() -> project.createEmptySequence(nameStep.getValue(), only.id()))
                .build(owner)
                .showAndWait();
        }

        var configStep = new ChoiceStep<>(
            "Simulation config",
            "Bind this sequence to a simulation config",
            configs,
            SimulationConfigDocument::name,
            cfg -> cfg.config() == null ? "" :
                String.format("B\u2080 %.3f T \u00B7 %d field%s",
                    cfg.config().referenceB0Tesla(),
                    cfg.config().fields().size(),
                    cfg.config().fields().size() == 1 ? "" : "s"));

        return WizardDialog.<SequenceDocument>builder("New Sequence")
            .step(nameStep)
            .step(configStep)
            .resultFactory(() -> project.createEmptySequence(nameStep.getValue(), configStep.getValue().id()))
            .build(owner)
            .showAndWait();
    }
}
