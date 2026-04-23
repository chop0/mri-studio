package ax.xz.mri.ui.wizard;

import ax.xz.mri.model.sequence.SequenceStarter;
import ax.xz.mri.model.sequence.SequenceStarterLibrary;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.ui.viewmodel.ProjectSessionViewModel;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.Optional;

/**
 * New Sequence wizard.
 *
 * <p>Steps: Template &rarr; [Template Config (if the template has one)]
 * &rarr; Name &rarr; [Simulation Config (if multiple exist)]. Starters are
 * pulled from {@link SequenceStarterLibrary} and each inspects the chosen
 * config at build time so the seeded clips are calibrated to that config.
 *
 * <p>The template-config step delegates to the starter's {@link
 * SequenceStarter#configStep()} so the Blank starter skips it while CPMG and
 * CP surface pulse-count / echo-spacing controls.
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
                "Create a simulation config first. Sequences are edited against a config's channel layout.");
            alert.setTitle("No simulation configs");
            alert.setHeaderText("A simulation config is required");
            alert.initOwner(owner);
            alert.showAndWait();
            return Optional.empty();
        }

        var starterStep = new ChoiceStep<>(
            "Template",
            "Choose a starter for the sequence",
            SequenceStarterLibrary.all(),
            SequenceStarter::name,
            SequenceStarter::description);

        var templateConfigStep = new DelegatingStarterStep(starterStep);

        var nameStep = new NameStep("Enter a name for the sequence", "New Sequence") {
            @Override
            public void onEnter() {
                var starter = starterStep.getValue();
                if (starter != null && (getValue().isBlank() || getValue().equals("New Sequence"))) {
                    setValue(starter.name());
                }
                super.onEnter();
            }
        };

        // Skip the config picker if there's only one - auto-bind it.
        if (configs.size() == 1) {
            var only = configs.getFirst();
            return WizardDialog.<SequenceDocument>builder("New Sequence")
                .step(starterStep)
                .step(templateConfigStep)
                .step(nameStep)
                .resultFactory(() -> project.createSequenceFromStarter(
                    nameStep.getValue(), only.id(), starterStep.getValue()))
                .build(owner)
                .showAndWait();
        }

        var configStep = new ChoiceStep<>(
            "Simulation config",
            "Bind this sequence to a simulation config",
            configs,
            SimulationConfigDocument::name,
            cfg -> {
                var cfgData = cfg.config();
                if (cfgData == null) return "";
                var circuit = repo.circuit(cfgData.circuitId());
                int sources = circuit == null ? 0 : circuit.voltageSources().size();
                return String.format("B0 %.3f T, %d source%s",
                    cfgData.referenceB0Tesla(), sources, sources == 1 ? "" : "s");
            });

        return WizardDialog.<SequenceDocument>builder("New Sequence")
            .step(starterStep)
            .step(templateConfigStep)
            .step(nameStep)
            .step(configStep)
            .resultFactory(() -> project.createSequenceFromStarter(
                nameStep.getValue(), configStep.getValue().id(), starterStep.getValue()))
            .build(owner)
            .showAndWait();
    }

    /**
     * Wizard step that mirrors the selected starter's own config step. Starters
     * with no customisation (Blank) show a neutral placeholder and pass
     * validity straight through. Mirrors
     * {@code NewSimConfigWizard.DelegatingTemplateStep}.
     */
    private static final class DelegatingStarterStep implements WizardStep {
        private final ChoiceStep<SequenceStarter> starterStep;
        private final StackPane container = new StackPane();
        private final Label placeholder;
        private final BooleanBinding valid;

        DelegatingStarterStep(ChoiceStep<SequenceStarter> starterStep) {
            this.starterStep = starterStep;
            placeholder = new Label("No options for this template.");
            placeholder.setStyle("-fx-text-fill: #64748b;");
            container.getChildren().add(placeholder);
            container.setPadding(new Insets(20));
            valid = Bindings.createBooleanBinding(() -> {
                var starter = starterStep.getValue();
                if (starter == null) return true;
                var step = starter.configStep();
                return step == null || step.validProperty().get();
            });
        }

        @Override public String title() { return "Configure"; }
        @Override public Node content() { return container; }
        @Override public BooleanBinding validProperty() { return valid; }

        @Override
        public void onEnter() {
            container.getChildren().clear();
            var starter = starterStep.getValue();
            var step = starter == null ? null : starter.configStep();
            if (step == null) {
                container.getChildren().add(placeholder);
            } else {
                container.getChildren().add(step.content());
                step.onEnter();
            }
        }
    }
}
