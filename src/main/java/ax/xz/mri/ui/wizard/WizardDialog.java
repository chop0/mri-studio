package ax.xz.mri.ui.wizard;

import javafx.beans.binding.Bindings;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Full-window multi-step wizard dialog.
 *
 * <p>Layout: left sidebar with numbered step list, centre content area,
 * bottom bar with Cancel / Back / Next / Finish buttons.
 *
 * <p>Usage:
 * <pre>{@code
 * var result = WizardDialog.<MyResult>builder("Title")
 *     .step(step1)
 *     .step(step2)
 *     .resultFactory(() -> computeResult())
 *     .build(ownerStage)
 *     .showAndWait();
 * }</pre>
 */
public final class WizardDialog<T> {
	private final Stage stage;
	private final List<WizardStep> steps;
	private final Supplier<T> resultFactory;
	private final IntegerProperty currentIndex = new SimpleIntegerProperty(0);
	private T result;
	private boolean finished;

	private WizardDialog(Stage owner, String title, List<WizardStep> steps, Supplier<T> resultFactory) {
		this.steps = List.copyOf(steps);
		this.resultFactory = resultFactory;

		stage = new Stage();
		stage.initModality(Modality.APPLICATION_MODAL);
		stage.initOwner(owner);
		stage.setTitle(title);
		stage.setResizable(true);

		var root = buildUI();
		var scene = new Scene(root, 620, 420);

		// Inherit stylesheets from the owner
		if (owner != null && owner.getScene() != null) {
			scene.getStylesheets().addAll(owner.getScene().getStylesheets());
		}

		stage.setScene(scene);

		// Force initial step display (setting to -1 then 0 ensures the listener fires)
		currentIndex.set(-1);
		navigateTo(0);
	}

	/** Show the wizard and return the result (empty if cancelled). */
	public Optional<T> showAndWait() {
		stage.showAndWait();
		return finished ? Optional.ofNullable(result) : Optional.empty();
	}

	// --- UI construction ---

	private BorderPane buildUI() {
		var root = new BorderPane();
		root.getStyleClass().add("wizard-root");

		// Sidebar
		var sidebar = new VBox(2);
		sidebar.getStyleClass().add("wizard-sidebar");
		sidebar.setPadding(new Insets(16, 12, 16, 12));
		sidebar.setPrefWidth(160);
		sidebar.setMinWidth(160);
		for (int i = 0; i < steps.size(); i++) {
			sidebar.getChildren().add(buildStepLabel(i));
		}
		root.setLeft(sidebar);

		// Content area
		var contentPane = new StackPane();
		contentPane.getStyleClass().add("wizard-content");
		contentPane.setPadding(new Insets(20));
		currentIndex.addListener((obs, oldVal, newVal) -> {
			contentPane.getChildren().clear();
			int idx = newVal.intValue();
			if (idx >= 0 && idx < steps.size()) {
				contentPane.getChildren().add(steps.get(idx).content());
			}
		});
		root.setCenter(contentPane);

		// Button bar
		root.setBottom(buildButtonBar());

		return root;
	}

	private Label buildStepLabel(int index) {
		var step = steps.get(index);
		var label = new Label((index + 1) + ".  " + step.title());
		label.getStyleClass().add("wizard-step-label");
		label.setMaxWidth(Double.MAX_VALUE);
		label.setPadding(new Insets(6, 8, 6, 8));

		// Highlight active step
		currentIndex.addListener((obs, oldVal, newVal) -> {
			label.getStyleClass().removeAll("wizard-step-active", "wizard-step-completed");
			if (newVal.intValue() == index) {
				label.getStyleClass().add("wizard-step-active");
			} else if (newVal.intValue() > index) {
				label.getStyleClass().add("wizard-step-completed");
			}
		});

		return label;
	}

	private HBox buildButtonBar() {
		var cancelBtn = new Button("Cancel");
		cancelBtn.setOnAction(e -> stage.close());
		cancelBtn.setCancelButton(true);

		var backBtn = new Button("\u2190 Back");
		backBtn.disableProperty().bind(currentIndex.isEqualTo(0));
		backBtn.setOnAction(e -> navigateTo(currentIndex.get() - 1));

		var nextBtn = new Button("Next \u2192");
		nextBtn.visibleProperty().bind(currentIndex.lessThan(steps.size() - 1));
		nextBtn.managedProperty().bind(nextBtn.visibleProperty());
		nextBtn.setOnAction(e -> navigateTo(currentIndex.get() + 1));

		var finishBtn = new Button("Finish");
		finishBtn.visibleProperty().bind(currentIndex.isEqualTo(steps.size() - 1));
		finishBtn.managedProperty().bind(finishBtn.visibleProperty());
		finishBtn.setDefaultButton(true);
		finishBtn.setOnAction(e -> {
			finished = true;
			if (resultFactory != null) result = resultFactory.get();
			stage.close();
		});

		// Bind Next/Finish disable to current step validity
		currentIndex.addListener((obs, oldVal, newVal) -> {
			int idx = newVal.intValue();
			if (idx >= 0 && idx < steps.size()) {
				var valid = steps.get(idx).validProperty();
				nextBtn.disableProperty().unbind();
				nextBtn.disableProperty().bind(valid.not());
				finishBtn.disableProperty().unbind();
				finishBtn.disableProperty().bind(valid.not());
			}
		});

		var spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);

		var bar = new HBox(8, cancelBtn, spacer, backBtn, nextBtn, finishBtn);
		bar.getStyleClass().add("wizard-button-bar");
		bar.setAlignment(Pos.CENTER_RIGHT);
		bar.setPadding(new Insets(10, 16, 10, 16));
		return bar;
	}

	private void navigateTo(int index) {
		if (index < 0 || index >= steps.size()) return;
		currentIndex.set(index);
		steps.get(index).onEnter();
	}

	// --- Builder ---

	public static <T> Builder<T> builder(String title) {
		return new Builder<>(title);
	}

	public static final class Builder<T> {
		private final String title;
		private final List<WizardStep> steps = new ArrayList<>();
		private Supplier<T> resultFactory;

		private Builder(String title) {
			this.title = title;
		}

		public Builder<T> step(WizardStep step) {
			steps.add(step);
			return this;
		}

		public Builder<T> resultFactory(Supplier<T> factory) {
			this.resultFactory = factory;
			return this;
		}

		public WizardDialog<T> build(Stage owner) {
			if (steps.isEmpty()) throw new IllegalStateException("Wizard must have at least one step");
			return new WizardDialog<>(owner, title, steps, resultFactory);
		}
	}
}
