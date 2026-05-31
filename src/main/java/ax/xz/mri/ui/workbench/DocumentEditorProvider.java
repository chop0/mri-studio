package ax.xz.mri.ui.workbench;

import ax.xz.mri.ui.viewmodel.DocumentSnapshot;
import ax.xz.mri.ui.viewmodel.StudioSession;
import javafx.scene.Node;

/**
 * Extension point for document editors. Each document type (sequence, sim
 * config, hardware config, eigenfield) provides its own editor UI which
 * fills the document tab in full — including any tool/analysis chrome the
 * document needs.
 */
public interface DocumentEditorProvider {

	/** The per-document editor UI (placed in the BentoFX document tab). */
	Node editorContent();

	/** Push this document's data into the shared session (called on tab focus). */
	void activate(StudioSession session);

	/** Save global tool state for this document (called on tab blur). */
	default DocumentSnapshot captureState(StudioSession session) {
		return session.captureToolSnapshot();
	}

	/** Restore global tool state for this document (called on tab focus). */
	default void restoreState(StudioSession session, DocumentSnapshot snapshot) {
		session.restoreToolSnapshot(snapshot);
	}

	/**
	 * Release any per-tab resources (background threads, observers, plugin
	 * sessions). Default is a no-op for editors whose state is purely
	 * UI-bound and tracked by their {@link DocumentSnapshot}.
	 */
	default void dispose() {}
}
