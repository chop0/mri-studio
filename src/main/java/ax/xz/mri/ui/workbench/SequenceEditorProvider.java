package ax.xz.mri.ui.workbench;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.service.simulation.compiled.CompiledSimulation;
import ax.xz.mri.project.HardwareConfigDocument;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.ui.viewmodel.DocumentSnapshot;
import ax.xz.mri.ui.viewmodel.HardwareRunSession;
import ax.xz.mri.ui.edit.EditSession;
import ax.xz.mri.ui.sim.SimDispatcher;
import ax.xz.mri.ui.viewmodel.StudioSession;
import ax.xz.mri.ui.pane.SequenceEditorPane;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import java.util.List;

/**
 * Editor provider for sequences. Content: inline config summary + DAW editor.
 * Timeline is a global tool window (not per-doc — avoids duplicate AnimationTimers).
 */
public final class SequenceEditorProvider implements DocumentEditorProvider {

	private final SequenceDocument document;
	public final SequenceEditorPane editorPane;
	public final EditSession editSession;
	public final SimDispatcher simSession;
	public final HardwareRunSession hardwareSession;
	private final javafx.scene.layout.BorderPane root;
	private final HBox configStripContainer;
	private final StudioSession sessionRef;

	// Cached simulation result — restored on tab switch instead of re-simulating
	public CompiledSimulation cachedSimulation;
	public List<PulseSegment> cachedPulse;

	public SequenceEditorProvider(SequenceDocument document, StudioSession session,
	                               WorkbenchController controller) {
		this.document = document;
		this.editorPane = new SequenceEditorPane(new PaneContext(session, controller, PaneId.SEQUENCE_EDITOR));
		this.editSession = editorPane.editSession();
		this.simSession = session.newSimDispatcher(editSession);
		this.hardwareSession = new HardwareRunSession(editSession, session);

		editorPane.open(document);
		editorPane.wireSimSession(simSession);
		editorPane.wireHardwareSession(hardwareSession);

		// Restore the persisted hardware-config binding. The id is the source
		// of truth — we deliberately don't validate it here; the inspector,
		// timeline, and run session all resolve through the repo on demand
		// and degrade gracefully if the id refers to a deleted config.
		editSession.activeHardwareConfigId.set(document.preferredHardwareConfigId());

		// Wire the edit session's config association. When activeSimConfigId
		// changes (via undo/redo or setActiveSimConfig), point the edit session
		// at the corresponding doc; the dispatcher's listener cascade picks
		// up the change and triggers a debounced sim — no explicit simulate()
		// call needed (it would queue a duplicate task on the executor).
		editSession.activeSimConfigId.addListener((obs, oldId, newId) -> {
			if (newId != null) {
				var repo = session.state.current();
				var configDoc = repo.node(newId);
				if (configDoc instanceof ax.xz.mri.project.SimulationConfigDocument sc) {
					editSession.activeConfigDoc.set(sc);
				}
			}
		});

		// Load associated sim config from the document's persisted config ID
		var configId = document.activeSimConfigId();
		if (configId != null && session.state.current().simulation(configId) != null) {
			editSession.setOriginalSimConfigId(configId);
		}
		// Inline config summary strip (reactive — rebuilds when config changes)
		configStripContainer = new HBox();
		this.sessionRef = session;
		rebuildConfigStrip();
		session.project.explorer.structureRevision.addListener((obs, o, n) -> rebuildConfigStrip());
		editSession.activeConfig.addListener((obs, o, n) -> rebuildConfigStrip());

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

		var cfg = editSession.activeConfig.get();
		if (cfg == null) {
			configStripContainer.getChildren().add(new Label("No simulation config"));
		} else {
			var circuit = sessionRef.state.current().circuit(cfg.circuitId());
			int sourceCount = circuit == null ? 0 : circuit.voltageSources().size();
			configStripContainer.getChildren().addAll(
				new Label("B\u2080: " + String.format("%.4f T", cfg.referenceB0Tesla())),
				new Label("dt: " + ax.xz.mri.util.SiFormat.time(cfg.dtSeconds() * 1e6)),
				new Label(sourceCount + " source" + (sourceCount == 1 ? "" : "s"))
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

	@Override
	public void activate(StudioSession session) {
		session.activeEditSession.set(editSession);
		if (cachedSimulation != null) {
			session.pushResultForTabSwitch(
				new ax.xz.mri.model.scenario.RunResult.Simulation(cachedSimulation, cachedPulse));
		} else {
			// Debounced — coalesces with any sim already triggered by the
			// constructor's activeSimConfigId listener cascade so we don't
			// run the full grid Bloch sweep twice on tab open.
			simSession.markDirty();
		}
	}

	@Override
	public DocumentSnapshot captureState(StudioSession session) {
		cachedSimulation = session.document.simulation.get();
		cachedPulse = session.document.currentPulse.get();
		return session.captureToolSnapshot();
	}

	@Override
	public void dispose() {
		simSession.dispose();
		hardwareSession.dispose();
		editorPane.dispose();
	}

	public SequenceDocument document() { return document; }
}
