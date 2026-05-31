package ax.xz.mri.ui.workbench;

import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.ui.viewmodel.DocumentSnapshot;
import ax.xz.mri.ui.viewmodel.StudioSession;
import ax.xz.mri.ui.workbench.pane.SimulationConfigEditorPane;
import javafx.scene.Node;

/**
 * Editor provider for simulation configs. The editor pane fills the
 * document tab on its own.
 */
public final class SimConfigEditorProvider implements DocumentEditorProvider {
	private final SimulationConfigDocument configDoc;
	private final SimulationConfigEditorPane editorPane;

	public SimConfigEditorProvider(SimulationConfigDocument configDoc, StudioSession session,
	                                WorkbenchController controller) {
		this.configDoc = configDoc;
		this.editorPane = new SimulationConfigEditorPane(
			new PaneContext(session, controller, PaneId.SIM_CONFIG_EDITOR), configDoc);
	}

	@Override public Node editorContent() { return editorPane; }

	@Override
	public void activate(StudioSession session) {
		session.activeEditSession.set(null);
		// Preserve the current analysis data — don't clear it.
		// The config editor doesn't produce its own simulation, but the
		// user should still see the last sequence's analysis while editing.
	}

	public SimulationConfigDocument configDoc() { return configDoc; }
	public SimulationConfigEditorPane editorPane() { return editorPane; }
}
