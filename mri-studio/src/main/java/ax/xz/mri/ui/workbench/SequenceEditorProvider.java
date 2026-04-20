package ax.xz.mri.ui.workbench;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.ui.viewmodel.DocumentSnapshot;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import ax.xz.mri.ui.viewmodel.SequenceSimulationSession;
import ax.xz.mri.ui.viewmodel.StudioSession;
import ax.xz.mri.ui.workbench.pane.SequenceEditorPane;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import java.util.List;
import java.util.Set;

/**
 * Editor provider for sequences. Content: inline config summary + DAW editor.
 * Timeline is a global tool window (not per-doc — avoids duplicate AnimationTimers).
 */
public final class SequenceEditorProvider implements DocumentEditorProvider {
	private static final Set<PaneId> RELEVANT = Set.of(
		PaneId.TIMELINE, PaneId.CROSS_SECTION, PaneId.SPHERE,
		PaneId.PHASE_MAP_Z, PaneId.PHASE_MAP_R,
		PaneId.TRACE_PHASE, PaneId.TRACE_POLAR, PaneId.TRACE_MAGNITUDE);

	private final SequenceDocument document;
	public final SequenceEditorPane editorPane;
	public final SequenceEditSession editSession;
	public final SequenceSimulationSession simSession;
	private final javafx.scene.layout.BorderPane root;
	private final HBox configStripContainer;
	private final StudioSession sessionRef;

	/** The config state at last save — used for dirty detection. */
	private SimulationConfig savedConfig;

	// Cached simulation result — restored on tab switch instead of re-simulating
	public BlochData cachedBlochData;
	public List<PulseSegment> cachedPulse;

	public SequenceEditorProvider(SequenceDocument document, StudioSession session,
	                               WorkbenchController controller) {
		this.document = document;
		this.editorPane = new SequenceEditorPane(new PaneContext(session, controller, PaneId.SEQUENCE_EDITOR));
		this.editSession = editorPane.editSession();
		this.simSession = new SequenceSimulationSession(editSession, session);

		editorPane.open(document);
		editorPane.wireSimSession(simSession);

		// Wire the edit session's config association to the sim session.
		// When activeSimConfigId changes (via undo/redo or setActiveSimConfig),
		// load the corresponding config into the sim session for re-simulation.
		editSession.activeSimConfigId.addListener((obs, oldId, newId) -> {
			if (newId != null) {
				var repo = session.project.repository.get();
				var configDoc = repo.node(newId);
				if (configDoc instanceof ax.xz.mri.project.SimulationConfigDocument sc) {
					simSession.loadConfig(sc);
				}
			}
		});

		// Load associated sim config from the document's persisted config ID
		var configId = document.activeSimConfigId();
		if (configId != null && session.project.repository.get().simConfig(configId) != null) {
			editSession.setOriginalSimConfigId(configId);
		}
		savedConfig = simSession.activeConfig.get();

		// Inline config summary strip (reactive — rebuilds when config changes)
		configStripContainer = new HBox();
		this.sessionRef = session;
		rebuildConfigStrip();
		session.project.explorer.structureRevision.addListener((obs, o, n) -> rebuildConfigStrip());
		simSession.activeConfig.addListener((obs, o, n) -> rebuildConfigStrip());

		// Config strip on top, DAW editor fills remaining space (BorderPane guarantees this)
		root = new javafx.scene.layout.BorderPane();
		root.setTop(configStripContainer);
		root.setCenter(editorPane);
	}

	private void rebuildConfigStrip() {
		configStripContainer.getChildren().clear();
		configStripContainer.setSpacing(8);
		configStripContainer.setPadding(new Insets(2, 6, 2, 6));
		configStripContainer.getStyleClass().setAll("shell-tool-strip");

		// Read from the sim session's active config (most up-to-date, including live edits)
		var cfg = simSession.activeConfig.get();
		if (cfg == null) {
			configStripContainer.getChildren().add(new Label("No simulation config"));
		} else {
			configStripContainer.getChildren().addAll(
				new Label("B\u2080: " + String.format("%.4f T", cfg.referenceB0Tesla())),
				new Label("T\u2081: " + String.format("%.0f ms", cfg.t1Ms())),
				new Label("T\u2082: " + String.format("%.0f ms", cfg.t2Ms())),
				new Label(cfg.fields().size() + " fields")
			);
			var activeConfigId = editSession.activeSimConfigId.get();
			if (activeConfigId != null) {
				var editBtn = new Button("Edit Config\u2026");
				editBtn.setStyle("-fx-font-size: 10px;");
				editBtn.setOnAction(e -> sessionRef.project.openNode(activeConfigId));
				configStripContainer.getChildren().add(editBtn);
			}
		}
	}

	@Override public Node editorContent() { return root; }
	@Override public Set<PaneId> relevantToolWindows() { return RELEVANT; }

	@Override
	public void activate(StudioSession session) {
		session.activeEditSession.set(editSession);
		if (cachedBlochData != null) {
			session.pushDataForTabSwitch(cachedBlochData, cachedPulse);
		} else {
			simSession.simulate();
		}
	}

	@Override
	public DocumentSnapshot captureState(StudioSession session) {
		cachedBlochData = session.document.blochData.get();
		cachedPulse = session.document.currentPulse.get();
		return session.captureToolSnapshot();
	}

	@Override public boolean isDirty() { return editSession.isDirty(); }
	@Override public void save() { editorPane.savePublic(); }

	@Override
	public void dispose() {
		simSession.dispose();
		editorPane.dispose();
	}

	public SequenceDocument document() { return document; }
}
