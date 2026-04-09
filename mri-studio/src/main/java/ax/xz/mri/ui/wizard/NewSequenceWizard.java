package ax.xz.mri.ui.wizard;

import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.ui.viewmodel.ProjectSessionViewModel;
import javafx.stage.Stage;

import java.util.Optional;

/** New Sequence wizard: just a name. */
public final class NewSequenceWizard {
	private NewSequenceWizard() {}

	public static Optional<SequenceDocument> show(Stage owner, ProjectSessionViewModel project) {
		var nameStep = new NameStep("Enter a name for the sequence", "New Sequence");

		return WizardDialog.<SequenceDocument>builder("New Sequence")
			.step(nameStep)
			.resultFactory(() -> project.createEmptySequence(nameStep.getValue()))
			.build(owner)
			.showAndWait();
	}
}
