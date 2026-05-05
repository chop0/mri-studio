package ax.xz.mri.ui.workbench;

import ax.xz.mri.ui.viewmodel.DocumentSnapshot;
import software.coley.bentofx.dockable.Dockable;

/**
 * A workspace tab — an open document with its editor provider and BentoFX dockable.
 * Stores a DocumentSnapshot for restoring tool window state on tab switch.
 */
public final class WorkspaceTab {
	private final String id;
	private final String displayName;
	private final DocumentEditorProvider editor;
	private Dockable dockable;
	private DocumentSnapshot snapshot;

	public WorkspaceTab(String id, String displayName, DocumentEditorProvider editor) {
		this.id = id;
		this.displayName = displayName;
		this.editor = editor;
	}

	public String id() { return id; }

	public String displayName() { return displayName; }

	public String rawName() { return displayName; }
	public DocumentEditorProvider editor() { return editor; }
	public Dockable dockable() { return dockable; }
	public void setDockable(Dockable d) { this.dockable = d; }
	public DocumentSnapshot snapshot() { return snapshot; }
	public void setSnapshot(DocumentSnapshot s) { this.snapshot = s; }
}
