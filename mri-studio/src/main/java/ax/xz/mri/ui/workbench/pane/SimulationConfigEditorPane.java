package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.simulation.ControlType;
import ax.xz.mri.model.simulation.EigenfieldPreset;
import ax.xz.mri.model.simulation.FieldDefinition;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.service.ObjectFactory;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Editor panel for a SimulationConfig. Opens as a tab alongside the sequence
 * editor in the bottom dock area. Supports undo/redo and dirty tracking.
 */
public final class SimulationConfigEditorPane extends WorkbenchPane {
	private static final int MAX_UNDO = 100;

	private final VBox content = new VBox(12);
	private SimulationConfigDocument document;
	private SimulationConfig config;
	private SimulationConfig savedConfig; // last-saved state for dirty tracking
	private boolean suppressUpdates;

	private final ArrayDeque<SimulationConfig> undoStack = new ArrayDeque<>();
	private final ArrayDeque<SimulationConfig> redoStack = new ArrayDeque<>();
	private Runnable onTitleChanged;

	public SimulationConfigEditorPane(PaneContext paneContext, SimulationConfigDocument document) {
		super(paneContext);
		this.document = document;
		this.config = document.config();
		this.savedConfig = config;
		setPaneTitle("Config: " + document.name());

		content.setPadding(new Insets(10));
		var scroll = new ScrollPane(content);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

		var root = new VBox(scroll);
		VBox.setVgrow(scroll, Priority.ALWAYS);
		root.setFocusTraversable(true);

		// --- Keyboard shortcuts ---
		root.setOnKeyPressed(event -> {
			if (new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN).match(event)) {
				save();
				event.consume();
			} else if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN).match(event)) {
				redo();
				event.consume();
			} else if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN).match(event)) {
				undo();
				event.consume();
			}
		});
		root.setOnMouseClicked(e -> root.requestFocus());

		setPaneContent(root);
		rebuild();
	}

	// --- Undo / redo ---

	private void pushUndo() {
		if (undoStack.size() >= MAX_UNDO) undoStack.removeLast();
		undoStack.push(config);
		redoStack.clear();
	}

	private void undo() {
		if (undoStack.isEmpty()) return;
		redoStack.push(config);
		config = undoStack.pop();
		document = document.withConfig(config);
		persistAndNotify();
		rebuild();
	}

	private void redo() {
		if (redoStack.isEmpty()) return;
		undoStack.push(config);
		config = redoStack.pop();
		document = document.withConfig(config);
		persistAndNotify();
		rebuild();
	}

	// --- Dirty / save ---

	public boolean isDirty() { return !config.equals(savedConfig); }

	public String tabTitle() {
		return document.name() + (isDirty() ? " *" : "");
	}

	public void setOnTitleChanged(Runnable callback) { this.onTitleChanged = callback; }

	private void notifyTitleChanged() {
		if (onTitleChanged != null) onTitleChanged.run();
	}

	public void save() {
		var repo = paneContext.session().project.repository.get();
		repo.addSimConfig(document);
		paneContext.controller().saveProject();
		savedConfig = config;
		notifyTitleChanged();
	}

	public void savePublic() { save(); }

	// --- UI rebuild ---

	private void rebuild() {
		suppressUpdates = true;
		content.getChildren().clear();

		// --- Fields section ---
		content.getChildren().add(sectionHeader("Fields"));
		for (int i = 0; i < config.fields().size(); i++) {
			content.getChildren().add(buildFieldCard(i));
		}
		var addFieldButton = new Button("+ Add Field");
		addFieldButton.setOnAction(e -> addField());
		content.getChildren().add(addFieldButton);

		content.getChildren().add(new Separator());

		// --- Tissue Properties ---
		content.getChildren().add(sectionHeader("Tissue Properties"));
		var tissueGrid = new GridPane();
		tissueGrid.setHgap(8);
		tissueGrid.setVgap(6);

		var t1Spinner = doubleSpinner(0.1, 10000, config.t1Ms(), 10);
		t1Spinner.valueProperty().addListener((obs, o, n) -> updateConfig(cfg ->
			new SimulationConfig(n, cfg.t2Ms(), cfg.gamma(), cfg.sliceHalfMm(), cfg.fovZMm(), cfg.fovRMm(), cfg.nZ(), cfg.nR(), cfg.fields(), cfg.isochromats())));
		tissueGrid.addRow(0, new Label("T\u2081 (ms)"), t1Spinner);

		var t2Spinner = doubleSpinner(0.1, 10000, config.t2Ms(), 10);
		t2Spinner.valueProperty().addListener((obs, o, n) -> updateConfig(cfg ->
			new SimulationConfig(cfg.t1Ms(), n, cfg.gamma(), cfg.sliceHalfMm(), cfg.fovZMm(), cfg.fovRMm(), cfg.nZ(), cfg.nR(), cfg.fields(), cfg.isochromats())));
		tissueGrid.addRow(1, new Label("T\u2082 (ms)"), t2Spinner);

		var gammaSpinner = doubleSpinner(1e6, 1e9, config.gamma(), 1e6);
		gammaSpinner.valueProperty().addListener((obs, o, n) -> updateConfig(cfg ->
			new SimulationConfig(cfg.t1Ms(), cfg.t2Ms(), n, cfg.sliceHalfMm(), cfg.fovZMm(), cfg.fovRMm(), cfg.nZ(), cfg.nR(), cfg.fields(), cfg.isochromats())));
		tissueGrid.addRow(2, new Label("\u03b3 (rad/s/T)"), gammaSpinner);

		content.getChildren().add(tissueGrid);
		content.getChildren().add(new Separator());

		// --- Spatial Grid ---
		content.getChildren().add(sectionHeader("Spatial Grid"));
		var spatialGrid = new GridPane();
		spatialGrid.setHgap(8);
		spatialGrid.setVgap(6);

		var fovZSpinner = doubleSpinner(0.1, 500, config.fovZMm(), 1);
		fovZSpinner.valueProperty().addListener((obs, o, n) -> updateConfig(cfg ->
			new SimulationConfig(cfg.t1Ms(), cfg.t2Ms(), cfg.gamma(), cfg.sliceHalfMm(), n, cfg.fovRMm(), cfg.nZ(), cfg.nR(), cfg.fields(), cfg.isochromats())));
		spatialGrid.addRow(0, new Label("FOV Z (mm)"), fovZSpinner);

		var fovRSpinner = doubleSpinner(0.1, 500, config.fovRMm(), 1);
		fovRSpinner.valueProperty().addListener((obs, o, n) -> updateConfig(cfg ->
			new SimulationConfig(cfg.t1Ms(), cfg.t2Ms(), cfg.gamma(), cfg.sliceHalfMm(), cfg.fovZMm(), n, cfg.nZ(), cfg.nR(), cfg.fields(), cfg.isochromats())));
		spatialGrid.addRow(1, new Label("FOV R (mm)"), fovRSpinner);

		var nZSpinner = intSpinner(2, 500, config.nZ());
		nZSpinner.valueProperty().addListener((obs, o, n) -> updateConfig(cfg ->
			new SimulationConfig(cfg.t1Ms(), cfg.t2Ms(), cfg.gamma(), cfg.sliceHalfMm(), cfg.fovZMm(), cfg.fovRMm(), n, cfg.nR(), cfg.fields(), cfg.isochromats())));
		spatialGrid.addRow(2, new Label("Grid Z"), nZSpinner);

		var nRSpinner = intSpinner(2, 100, config.nR());
		nRSpinner.valueProperty().addListener((obs, o, n) -> updateConfig(cfg ->
			new SimulationConfig(cfg.t1Ms(), cfg.t2Ms(), cfg.gamma(), cfg.sliceHalfMm(), cfg.fovZMm(), cfg.fovRMm(), cfg.nZ(), n, cfg.fields(), cfg.isochromats())));
		spatialGrid.addRow(3, new Label("Grid R"), nRSpinner);

		var sliceSpinner = doubleSpinner(0.1, 100, config.sliceHalfMm(), 0.5);
		sliceSpinner.valueProperty().addListener((obs, o, n) -> updateConfig(cfg ->
			new SimulationConfig(cfg.t1Ms(), cfg.t2Ms(), cfg.gamma(), n, cfg.fovZMm(), cfg.fovRMm(), cfg.nZ(), cfg.nR(), cfg.fields(), cfg.isochromats())));
		spatialGrid.addRow(4, new Label("Slice half (mm)"), sliceSpinner);

		content.getChildren().add(spatialGrid);
		content.getChildren().add(new Separator());

		// --- Isochromats ---
		content.getChildren().add(sectionHeader("Isochromats"));
		for (int i = 0; i < config.isochromats().size(); i++) {
			content.getChildren().add(buildIsoRow(i));
		}
		var addIsoButton = new Button("+ Add Isochromat");
		addIsoButton.setOnAction(e -> addIsochromat());
		content.getChildren().add(addIsoButton);

		suppressUpdates = false;
	}

	// --- Field card builder ---

	private Node buildFieldCard(int index) {
		var field = config.fields().get(index);
		var card = new VBox(4);
		card.getStyleClass().add("field-card");

		// Row 1: name + control type + remove
		var nameField = new TextField(field.name());
		nameField.setPrefWidth(120);
		nameField.focusedProperty().addListener((obs, o, focused) -> {
			if (!focused) updateField(index, config.fields().get(index).withName(nameField.getText()));
		});

		var controlCombo = new ComboBox<ControlType>();
		controlCombo.getItems().addAll(ControlType.values());
		controlCombo.setValue(field.controlType());
		controlCombo.setOnAction(e -> updateField(index, config.fields().get(index).withControlType(controlCombo.getValue())));

		var removeButton = new Button("\u2715");
		removeButton.setOnAction(e -> removeField(index));

		var spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		var headerRow = new HBox(8, nameField, controlCombo, spacer, removeButton);
		headerRow.setAlignment(Pos.CENTER_LEFT);
		card.getChildren().add(headerRow);

		// Row 2: amplitude range
		var ampGrid = new GridPane();
		ampGrid.setHgap(8);
		ampGrid.setVgap(4);

		var minSpinner = doubleSpinner(-1e6, 1e6, field.minAmplitude(), 0.001);
		minSpinner.valueProperty().addListener((obs, o, n) -> updateField(index, config.fields().get(index).withMinAmplitude(n)));
		minSpinner.setDisable(field.controlType() == ControlType.BINARY);

		var maxSpinner = doubleSpinner(-1e6, 1e6, field.maxAmplitude(), 0.001);
		maxSpinner.valueProperty().addListener((obs, o, n) -> updateField(index, config.fields().get(index).withMaxAmplitude(n)));

		ampGrid.addRow(0, new Label("Min amplitude"), minSpinner, new Label("Max"), maxSpinner);
		card.getChildren().add(ampGrid);

		// Row 3: baseband frequency
		var freqGrid = new GridPane();
		freqGrid.setHgap(8);
		var freqSpinner = doubleSpinner(0, 1e9, field.basebandFrequencyHz(), 1000);
		freqSpinner.valueProperty().addListener((obs, o, n) -> updateField(index, config.fields().get(index).withBasebandFrequencyHz(n)));
		var freqLabel = new Label(field.basebandFrequencyHz() == 0 ? "DC" : formatFrequency(field.basebandFrequencyHz()));
		freqSpinner.valueProperty().addListener((obs, o, n) ->
			freqLabel.setText(n == 0 ? "DC" : formatFrequency(n)));
		freqGrid.addRow(0, new Label("Baseband"), freqSpinner, freqLabel);
		card.getChildren().add(freqGrid);

		// Row 4: eigenfield reference
		card.getChildren().add(buildEigenfieldSelector(index, field));

		return card;
	}

	private Node buildEigenfieldSelector(int fieldIndex, FieldDefinition field) {
		var repo = paneContext.session().project.repository.get();
		var combo = new ComboBox<String>();
		var idMap = new java.util.LinkedHashMap<String, ProjectNodeId>();

		for (var efId : repo.eigenfieldIds()) {
			var efNode = repo.node(efId);
			if (efNode instanceof EigenfieldDocument ef) {
				String display = ef.name() + " (" + ef.preset().displayName() + ")";
				combo.getItems().add(display);
				idMap.put(display, ef.id());
				if (ef.id().equals(field.eigenfieldId())) {
					combo.setValue(display);
				}
			}
		}

		combo.setOnAction(e -> {
			var selectedId = idMap.get(combo.getValue());
			if (selectedId != null) {
				updateField(fieldIndex, config.fields().get(fieldIndex).withEigenfieldId(selectedId));
			}
		});

		return new HBox(8, new Label("Eigenfield"), combo);
	}

	// --- Isochromat row ---

	private Node buildIsoRow(int index) {
		var iso = config.isochromats().get(index);
		var nameField = new TextField(iso.name());
		nameField.setPrefWidth(100);
		var rSpinner = doubleSpinner(-500, 500, iso.rMm(), 0.5);
		var zSpinner = doubleSpinner(-500, 500, iso.zMm(), 0.5);
		var colourField = new TextField(iso.colour());
		colourField.setPrefWidth(80);
		var removeButton = new Button("\u2715");

		Runnable commit = () -> {
			var updated = new SimulationConfig.IsoPoint(rSpinner.getValue(), zSpinner.getValue(), nameField.getText(), colourField.getText());
			var list = new ArrayList<>(config.isochromats());
			list.set(index, updated);
			updateConfig(cfg -> cfg.withIsochromats(list));
		};
		nameField.focusedProperty().addListener((obs, o, f) -> { if (!f) commit.run(); });
		rSpinner.valueProperty().addListener((obs, o, n) -> commit.run());
		zSpinner.valueProperty().addListener((obs, o, n) -> commit.run());
		colourField.focusedProperty().addListener((obs, o, f) -> { if (!f) commit.run(); });
		removeButton.setOnAction(e -> removeIsochromat(index));

		return new HBox(6, nameField, new Label("r"), rSpinner, new Label("z"), zSpinner, colourField, removeButton);
	}

	// --- Mutation helpers ---

	private void updateField(int index, FieldDefinition updated) {
		var list = new ArrayList<>(config.fields());
		list.set(index, updated);
		updateConfig(cfg -> cfg.withFields(list));
	}

	private void removeField(int index) {
		var list = new ArrayList<>(config.fields());
		list.remove(index);
		updateConfig(cfg -> cfg.withFields(list));
		rebuild();
	}

	private void addField() {
		var repo = paneContext.session().project.repository.get();

		// Offer to select an existing eigenfield or create a new one
		var options = new ArrayList<String>();
		var idMap = new java.util.LinkedHashMap<String, ProjectNodeId>();
		for (var efId : repo.eigenfieldIds()) {
			var efNode = repo.node(efId);
			if (efNode instanceof EigenfieldDocument ef) {
				String display = ef.name() + " (" + ef.preset().displayName() + ")";
				options.add(display);
				idMap.put(display, ef.id());
			}
		}
		String createNew = "Create new eigenfield\u2026";
		options.add(createNew);

		var dialog = new ChoiceDialog<>(options.isEmpty() ? createNew : options.getFirst(), options);
		dialog.setTitle("Add Field");
		dialog.setHeaderText("Select eigenfield for the new field");
		dialog.setContentText("Eigenfield:");

		dialog.showAndWait().ifPresent(choice -> {
			ProjectNodeId eigenfieldId;
			if (choice.equals(createNew)) {
				eigenfieldId = createNewEigenfieldDialog(repo);
				if (eigenfieldId == null) return;
			} else {
				eigenfieldId = idMap.get(choice);
				if (eigenfieldId == null) return;
			}
			var list = new ArrayList<>(config.fields());
			list.add(new FieldDefinition("New Field", ControlType.LINEAR, 0, 1, 0, eigenfieldId));
			updateConfig(cfg -> cfg.withFields(list));
			paneContext.session().project.explorer.refresh();
			rebuild();
		});
	}

	private ProjectNodeId createNewEigenfieldDialog(ax.xz.mri.project.ProjectRepository repo) {
		var presetDialog = new ChoiceDialog<>(EigenfieldPreset.UNIFORM_BZ, Arrays.asList(EigenfieldPreset.values()));
		presetDialog.setTitle("New Eigenfield");
		presetDialog.setHeaderText("Choose eigenfield preset");
		presetDialog.setContentText("Preset:");

		var preset = presetDialog.showAndWait().orElse(null);
		if (preset == null) return null;

		var nameDialog = new TextInputDialog(preset.displayName());
		nameDialog.setTitle("New Eigenfield");
		nameDialog.setHeaderText("Name:");

		var name = nameDialog.showAndWait().map(String::trim).filter(n -> !n.isBlank()).orElse(null);
		if (name == null) return null;

		var eigen = ObjectFactory.findOrCreateEigenfield(repo, name, preset.description(), preset);
		paneContext.session().project.explorer.refresh();
		return eigen.id();
	}

	private void addIsochromat() {
		var list = new ArrayList<>(config.isochromats());
		list.add(new SimulationConfig.IsoPoint(0, 0, "Point " + (list.size() + 1), "#888888"));
		updateConfig(cfg -> cfg.withIsochromats(list));
		rebuild();
	}

	private void removeIsochromat(int index) {
		var list = new ArrayList<>(config.isochromats());
		list.remove(index);
		updateConfig(cfg -> cfg.withIsochromats(list));
		rebuild();
	}

	private void updateConfig(java.util.function.UnaryOperator<SimulationConfig> updater) {
		if (suppressUpdates) return;
		pushUndo();
		config = updater.apply(config);
		document = document.withConfig(config);
		persistAndNotify();
	}

	private void persistAndNotify() {
		// Persist to repository (live — sim session picks up changes via debounce)
		var repo = paneContext.session().project.repository.get();
		repo.addSimConfig(document);
		paneContext.session().project.saveProjectQuietly();

		// Push live to any active simulation session
		for (var simSession : paneContext.controller().allSimSessions()) {
			var activeDoc = simSession.activeConfigDoc.get();
			if (activeDoc != null && activeDoc.id().equals(document.id())) {
				simSession.updateConfigLive(config);
			}
		}

		notifyTitleChanged();
	}

	// --- Widget factories ---

	private static Label sectionHeader(String text) {
		var label = new Label(text);
		label.getStyleClass().add("section-header");
		return label;
	}

	private static Spinner<Double> doubleSpinner(double min, double max, double value, double step) {
		var spinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, value, step));
		spinner.setEditable(true);
		spinner.setPrefWidth(120);
		spinner.focusedProperty().addListener((obs, o, focused) -> {
			if (!focused) {
				try { spinner.increment(0); } catch (Exception ignored) {}
			}
		});
		return spinner;
	}

	private static Spinner<Integer> intSpinner(int min, int max, int value) {
		var spinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, value));
		spinner.setEditable(true);
		spinner.setPrefWidth(80);
		spinner.focusedProperty().addListener((obs, o, focused) -> {
			if (!focused) {
				try { spinner.increment(0); } catch (Exception ignored) {}
			}
		});
		return spinner;
	}

	private static String formatFrequency(double hz) {
		if (hz >= 1e6) return String.format("%.2f MHz", hz / 1e6);
		if (hz >= 1e3) return String.format("%.1f kHz", hz / 1e3);
		return String.format("%.0f Hz", hz);
	}
}
