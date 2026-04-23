package ax.xz.mri.ui.wizard;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/** Reusable wizard step: a single text field for entering a name. */
public class NameStep implements WizardStep {
	private final String stepTitle;
	private final String prompt;
	private final TextField field = new TextField();
	private final BooleanBinding valid;
	private final VBox root;

	public NameStep(String stepTitle, String prompt, String defaultValue) {
		this.stepTitle = stepTitle;
		this.prompt = prompt;
		field.setText(defaultValue);
		field.setPromptText(prompt);
		valid = Bindings.createBooleanBinding(
			() -> field.getText() != null && !field.getText().isBlank(),
			field.textProperty());

		var label = new Label(prompt);
		label.getStyleClass().add("section-header");
		root = new VBox(8, label, field);
		root.setPadding(new Insets(4));
	}

	public NameStep(String prompt, String defaultValue) {
		this("Name", prompt, defaultValue);
	}

	@Override public String title() { return stepTitle; }
	@Override public Node content() { return root; }
	@Override public BooleanBinding validProperty() { return valid; }
	@Override public void onEnter() { field.requestFocus(); field.selectAll(); }

	public String getValue() { return field.getText().trim(); }

	/** Update the text field value (used when a previous step changes the default). */
	public void setValue(String value) { field.setText(value); }
}
