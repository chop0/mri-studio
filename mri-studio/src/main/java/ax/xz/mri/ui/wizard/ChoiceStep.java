package ax.xz.mri.ui.wizard;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Function;

/**
 * Reusable wizard step: pick one item from a list.
 * Each item is displayed with a title and description.
 */
public final class ChoiceStep<T> implements WizardStep {
	private final String stepTitle;
	private final ListView<T> listView = new ListView<>();
	private final BooleanBinding valid;
	private final VBox root;

	public ChoiceStep(String stepTitle, String prompt, List<T> items,
	                  Function<T, String> titleFn, Function<T, String> descriptionFn) {
		this.stepTitle = stepTitle;
		listView.getItems().addAll(items);
		listView.setPrefHeight(250);
		listView.setCellFactory(lv -> new ListCell<>() {
			@Override
			protected void updateItem(T item, boolean empty) {
				super.updateItem(item, empty);
				if (empty || item == null) {
					setGraphic(null);
					return;
				}
				var title = new Label(titleFn.apply(item));
				title.setStyle("-fx-font-weight: bold;");
				var desc = new Label(descriptionFn.apply(item));
				desc.setStyle("-fx-text-fill: #64748b; -fx-font-size: 11px;");
				desc.setWrapText(true);
				setGraphic(new VBox(2, title, desc));
			}
		});
		if (!items.isEmpty()) listView.getSelectionModel().selectFirst();

		valid = Bindings.createBooleanBinding(
			() -> listView.getSelectionModel().getSelectedItem() != null,
			listView.getSelectionModel().selectedItemProperty());

		var label = new Label(prompt);
		label.getStyleClass().add("section-header");
		root = new VBox(8, label, listView);
		root.setPadding(new Insets(4));
	}

	@Override public String title() { return stepTitle; }
	@Override public Node content() { return root; }
	@Override public BooleanBinding validProperty() { return valid; }
	@Override public void onEnter() { listView.requestFocus(); }

	public T getValue() { return listView.getSelectionModel().getSelectedItem(); }
}
