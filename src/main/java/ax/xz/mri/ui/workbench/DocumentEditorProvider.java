package ax.xz.mri.ui.workbench;

import ax.xz.mri.ui.viewmodel.DocumentSnapshot;
import ax.xz.mri.ui.viewmodel.StudioSession;
import javafx.scene.Node;

import java.util.Set;

/**
 * Extension point for document editors. Each document type (sequence, import,
 * sim config, etc.) provides its own editor content, declares which tool
 * windows are relevant, and knows how to push its data into the shared session.
 */
public interface DocumentEditorProvider {

	/** The per-document editor UI (placed in the BentoFX document tab). */
	Node editorContent();

	/** Which tool windows should be active when this document is focused. */
	Set<PaneId> relevantToolWindows();

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

	void dispose();
}
