package ax.xz.mri.ui.wizard;

import javafx.beans.binding.BooleanBinding;
import javafx.scene.Node;

/**
 * A single page in a multi-step wizard.
 * Implement this to define the UI and validation for one step.
 */
public interface WizardStep {
	/** Title shown in the sidebar step list. */
	String title();

	/** The UI content for this step. Created once and reused. */
	Node content();

	/** Observable that is true when this step's input is valid (enables Next/Finish). */
	BooleanBinding validProperty();

	/** Called each time this step becomes the active step. */
	default void onEnter() {}
}
