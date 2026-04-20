package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.FieldDefinition;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
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
import java.util.LinkedHashMap;

/**
 * Editor for a {@link SimulationConfigDocument}. Shows tissue + spatial
 * parameters, a scalar reference B0 (the rotating-frame tuning), a list of
 * field cards (name + kind + eigenfield + carrier + bounds), and isochromats.
 *
 * <p>Live-subscribes to repository changes so eigenfield renames elsewhere
 * reflect immediately in the field-card dropdowns.
 */
public final class SimulationConfigEditorPane extends WorkbenchPane {
	private static final int MAX_UNDO = 100;

	private final VBox content = new VBox(12);
	private SimulationConfigDocument document;
	private SimulationConfig config;
	private SimulationConfig savedConfig;
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

		// Live-sync when eigenfields are renamed / added / removed elsewhere.
		paneContext.session().project.explorer.structureRevision.addListener((obs, o, n) -> rebuild());
	}

	// --- Undo / redo / save ---

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

		// Reference B0 / Larmor
		content.getChildren().add(sectionHeader("Reference B\u2080"));
		var b0Grid = new GridPane();
		b0Grid.setHgap(8);
		b0Grid.setVgap(6);
		var b0Spinner = doubleSpinner(0, 30, config.referenceB0Tesla(), 0.001);
		b0Spinner.valueProperty().addListener((obs, o, n) ->
			updateConfig(c -> c.withReferenceB0Tesla(n)));
		var larmorLabel = new Label();
		Runnable refreshLarmor = () -> larmorLabel.setText(String.format(
			"\u03c9_s / 2\u03c0 = %.2f MHz", config.gamma() * config.referenceB0Tesla() / (2 * Math.PI) / 1e6));
		refreshLarmor.run();
		b0Spinner.valueProperty().addListener((obs, o, n) -> refreshLarmor.run());
		b0Grid.addRow(0, new Label("Reference B\u2080 (T)"), b0Spinner, larmorLabel);
		content.getChildren().add(b0Grid);

		content.getChildren().add(new Separator());

		// Fields
		content.getChildren().add(sectionHeader("Fields"));
		for (int i = 0; i < config.fields().size(); i++) {
			content.getChildren().add(buildFieldCard(i));
		}
		var addFieldButton = new Button("+ Add Field");
		addFieldButton.setOnAction(e -> addField());
		content.getChildren().add(addFieldButton);

		content.getChildren().add(new Separator());

		// Tissue
		content.getChildren().add(sectionHeader("Tissue Properties"));
		var tissueGrid = new GridPane();
		tissueGrid.setHgap(8);
		tissueGrid.setVgap(6);

		var t1Spinner = doubleSpinner(0.1, 10000, config.t1Ms(), 10);
		t1Spinner.valueProperty().addListener((obs, o, n) ->
			updateConfig(c -> new SimulationConfig(n, c.t2Ms(), c.gamma(),
				c.sliceHalfMm(), c.fovZMm(), c.fovRMm(), c.nZ(), c.nR(),
				c.referenceB0Tesla(), c.fields(), c.isochromats())));
		tissueGrid.addRow(0, new Label("T\u2081 (ms)"), t1Spinner);

		var t2Spinner = doubleSpinner(0.1, 10000, config.t2Ms(), 10);
		t2Spinner.valueProperty().addListener((obs, o, n) ->
			updateConfig(c -> new SimulationConfig(c.t1Ms(), n, c.gamma(),
				c.sliceHalfMm(), c.fovZMm(), c.fovRMm(), c.nZ(), c.nR(),
				c.referenceB0Tesla(), c.fields(), c.isochromats())));
		tissueGrid.addRow(1, new Label("T\u2082 (ms)"), t2Spinner);

		var gammaSpinner = doubleSpinner(1e6, 1e9, config.gamma(), 1e6);
		gammaSpinner.valueProperty().addListener((obs, o, n) ->
			updateConfig(c -> new SimulationConfig(c.t1Ms(), c.t2Ms(), n,
				c.sliceHalfMm(), c.fovZMm(), c.fovRMm(), c.nZ(), c.nR(),
				c.referenceB0Tesla(), c.fields(), c.isochromats())));
		tissueGrid.addRow(2, new Label("\u03b3 (rad/s/T)"), gammaSpinner);

		content.getChildren().add(tissueGrid);
		content.getChildren().add(new Separator());

		// Spatial grid
		content.getChildren().add(sectionHeader("Spatial Grid"));
		var spatialGrid = new GridPane();
		spatialGrid.setHgap(8);
		spatialGrid.setVgap(6);

		var fovZSpinner = doubleSpinner(0.1, 500, config.fovZMm(), 1);
		fovZSpinner.valueProperty().addListener((obs, o, n) ->
			updateConfig(c -> new SimulationConfig(c.t1Ms(), c.t2Ms(), c.gamma(),
				c.sliceHalfMm(), n, c.fovRMm(), c.nZ(), c.nR(),
				c.referenceB0Tesla(), c.fields(), c.isochromats())));
		spatialGrid.addRow(0, new Label("FOV Z (mm)"), fovZSpinner);

		var fovRSpinner = doubleSpinner(0.1, 500, config.fovRMm(), 1);
		fovRSpinner.valueProperty().addListener((obs, o, n) ->
			updateConfig(c -> new SimulationConfig(c.t1Ms(), c.t2Ms(), c.gamma(),
				c.sliceHalfMm(), c.fovZMm(), n, c.nZ(), c.nR(),
				c.referenceB0Tesla(), c.fields(), c.isochromats())));
		spatialGrid.addRow(1, new Label("FOV R (mm)"), fovRSpinner);

		var nZSpinner = intSpinner(2, 500, config.nZ());
		nZSpinner.valueProperty().addListener((obs, o, n) ->
			updateConfig(c -> new SimulationConfig(c.t1Ms(), c.t2Ms(), c.gamma(),
				c.sliceHalfMm(), c.fovZMm(), c.fovRMm(), n, c.nR(),
				c.referenceB0Tesla(), c.fields(), c.isochromats())));
		spatialGrid.addRow(2, new Label("Grid Z"), nZSpinner);

		var nRSpinner = intSpinner(2, 100, config.nR());
		nRSpinner.valueProperty().addListener((obs, o, n) ->
			updateConfig(c -> new SimulationConfig(c.t1Ms(), c.t2Ms(), c.gamma(),
				c.sliceHalfMm(), c.fovZMm(), c.fovRMm(), c.nZ(), n,
				c.referenceB0Tesla(), c.fields(), c.isochromats())));
		spatialGrid.addRow(3, new Label("Grid R"), nRSpinner);

		var sliceSpinner = doubleSpinner(0.1, 100, config.sliceHalfMm(), 0.5);
		sliceSpinner.valueProperty().addListener((obs, o, n) ->
			updateConfig(c -> new SimulationConfig(c.t1Ms(), c.t2Ms(), c.gamma(),
				n, c.fovZMm(), c.fovRMm(), c.nZ(), c.nR(),
				c.referenceB0Tesla(), c.fields(), c.isochromats())));
		spatialGrid.addRow(4, new Label("Slice half (mm)"), sliceSpinner);

		content.getChildren().add(spatialGrid);
		content.getChildren().add(new Separator());

		// Isochromats
		content.getChildren().add(sectionHeader("Isochromats"));
		for (int i = 0; i < config.isochromats().size(); i++) {
			content.getChildren().add(buildIsoRow(i));
		}
		var addIsoButton = new Button("+ Add Isochromat");
		addIsoButton.setOnAction(e -> addIsochromat());
		content.getChildren().add(addIsoButton);

		suppressUpdates = false;
	}

	// --- Field card ---

	private Node buildFieldCard(int index) {
		var field = config.fields().get(index);
		var card = new VBox(4);
		card.getStyleClass().add("field-card");

		var nameField = new TextField(field.name());
		nameField.setPrefWidth(140);
		nameField.focusedProperty().addListener((obs, o, focused) -> {
			if (!focused) updateField(index, config.fields().get(index).withName(nameField.getText()));
		});

		var kindCombo = new ComboBox<AmplitudeKind>();
		kindCombo.getItems().addAll(AmplitudeKind.values());
		kindCombo.setValue(field.kind());
		kindCombo.setOnAction(e ->
			updateField(index, config.fields().get(index).withKind(kindCombo.getValue())));

		var removeButton = new Button("\u2715");
		removeButton.setOnAction(e -> removeField(index));

		var spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		var headerRow = new HBox(8, nameField, kindCombo, spacer, removeButton);
		headerRow.setAlignment(Pos.CENTER_LEFT);
		card.getChildren().add(headerRow);

		// Amplitude bounds
		var ampGrid = new GridPane();
		ampGrid.setHgap(8);
		ampGrid.setVgap(4);
		var minSpinner = doubleSpinner(-1e6, 1e6, field.minAmplitude(), 0.001);
		minSpinner.valueProperty().addListener((obs, o, n) ->
			updateField(index, config.fields().get(index).withMinAmplitude(n)));
		minSpinner.setDisable(field.kind() == AmplitudeKind.STATIC);
		var maxSpinner = doubleSpinner(-1e6, 1e6, field.maxAmplitude(), 0.001);
		maxSpinner.valueProperty().addListener((obs, o, n) ->
			updateField(index, config.fields().get(index).withMaxAmplitude(n)));
		ampGrid.addRow(0, new Label("Min"), minSpinner, new Label("Max"), maxSpinner);
		card.getChildren().add(ampGrid);

		// Carrier (only meaningful for QUADRATURE)
		if (field.kind() == AmplitudeKind.QUADRATURE) {
			var freqGrid = new GridPane();
			freqGrid.setHgap(8);
			var freqSpinner = doubleSpinner(0, 1e9, field.carrierHz(), 1000);
			freqSpinner.valueProperty().addListener((obs, o, n) ->
				updateField(index, config.fields().get(index).withCarrierHz(n)));
			var freqLabel = new Label(formatFrequency(field.carrierHz()));
			freqSpinner.valueProperty().addListener((obs, o, n) ->
				freqLabel.setText(formatFrequency(n == null ? 0 : n)));
			freqGrid.addRow(0, new Label("Carrier"), freqSpinner, freqLabel);
			card.getChildren().add(freqGrid);
		}

		// Eigenfield selector
		card.getChildren().add(buildEigenfieldSelector(index, field));

		return card;
	}

	private Node buildEigenfieldSelector(int fieldIndex, FieldDefinition field) {
		var repo = paneContext.session().project.repository.get();
		var combo = new ComboBox<String>();
		var idMap = new LinkedHashMap<String, ProjectNodeId>();

		for (var efId : repo.eigenfieldIds()) {
			var efNode = repo.node(efId);
			if (efNode instanceof EigenfieldDocument ef) {
				String display = ef.name();
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

		var openButton = new Button("Open");
		openButton.setOnAction(e -> {
			if (field.eigenfieldId() != null) {
				paneContext.session().project.openNode(field.eigenfieldId());
			}
		});
		openButton.setDisable(field.eigenfieldId() == null);

		var newButton = new Button("New…");
		newButton.setOnAction(e -> {
			var mainStage = javafx.stage.Window.getWindows().stream()
				.filter(w -> w instanceof javafx.stage.Stage && w.isShowing())
				.map(w -> (javafx.stage.Stage) w).findFirst().orElse(null);
			ax.xz.mri.ui.wizard.NewEigenfieldWizard.show(mainStage, paneContext.session().project)
				.ifPresent(eigen ->
					updateField(fieldIndex, config.fields().get(fieldIndex).withEigenfieldId(eigen.id())));
		});

		return new HBox(8, new Label("Eigenfield"), combo, openButton, newButton);
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
			updateConfig(c -> c.withIsochromats(list));
		};
		nameField.focusedProperty().addListener((obs, o, f) -> { if (!f) commit.run(); });
		rSpinner.valueProperty().addListener((obs, o, n) -> commit.run());
		zSpinner.valueProperty().addListener((obs, o, n) -> commit.run());
		colourField.focusedProperty().addListener((obs, o, f) -> { if (!f) commit.run(); });
		removeButton.setOnAction(e -> removeIsochromat(index));

		return new HBox(6, nameField, new Label("r"), rSpinner, new Label("z"), zSpinner, colourField, removeButton);
	}

	// --- Mutation ---

	private void updateField(int index, FieldDefinition updated) {
		var list = new ArrayList<>(config.fields());
		list.set(index, updated);
		updateConfig(cfg -> cfg.withFields(list));
		rebuild();
	}

	private void removeField(int index) {
		var list = new ArrayList<>(config.fields());
		list.remove(index);
		updateConfig(cfg -> cfg.withFields(list));
		rebuild();
	}

	private void addField() {
		var repo = paneContext.session().project.repository.get();
		var efIds = repo.eigenfieldIds();
		if (efIds.isEmpty()) {
			// If the project has no eigenfields yet, force the user through the wizard.
			var mainStage = javafx.stage.Window.getWindows().stream()
				.filter(w -> w instanceof javafx.stage.Stage && w.isShowing())
				.map(w -> (javafx.stage.Stage) w).findFirst().orElse(null);
			ax.xz.mri.ui.wizard.NewEigenfieldWizard.show(mainStage, paneContext.session().project)
				.ifPresent(eigen -> appendField(eigen.id()));
			return;
		}
		appendField(efIds.get(0));
	}

	private void appendField(ProjectNodeId eigenfieldId) {
		var list = new ArrayList<>(config.fields());
		list.add(new FieldDefinition("New Field", eigenfieldId, AmplitudeKind.REAL, 0, -1, 1));
		updateConfig(cfg -> cfg.withFields(list));
		paneContext.session().project.explorer.refresh();
		rebuild();
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
		var repo = paneContext.session().project.repository.get();
		repo.addSimConfig(document);
		paneContext.session().project.saveProjectQuietly();

		for (var simSession : paneContext.controller().allSimSessions()) {
			var activeDoc = simSession.activeConfigDoc.get();
			if (activeDoc != null && activeDoc.id().equals(document.id())) {
				simSession.updateConfigLive(config);
			}
		}

		notifyTitleChanged();
	}

	// --- Widgets ---

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
