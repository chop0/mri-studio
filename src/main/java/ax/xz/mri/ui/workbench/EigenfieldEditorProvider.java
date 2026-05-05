package ax.xz.mri.ui.workbench;

import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.ui.eigenfield.EigenfieldEditorPane;
import ax.xz.mri.ui.viewmodel.StudioSession;
import javafx.scene.Node;

import java.util.Set;

/**
 * Editor provider for {@link EigenfieldDocument}. Content: the script editor
 * plus live 3D preview. No analysis tool windows are relevant (the field
 * shape is self-contained — not a time-domain simulation artefact).
 */
public final class EigenfieldEditorProvider implements DocumentEditorProvider {
    private final EigenfieldDocument document;
    private final EigenfieldEditorPane editorPane;

    public EigenfieldEditorProvider(EigenfieldDocument document, StudioSession session,
                                    WorkbenchController controller) {
        this.document = document;
        this.editorPane = new EigenfieldEditorPane(
            new PaneContext(session, controller, PaneId.EIGENFIELD_EDITOR), document);
    }

    @Override public Node editorContent() { return editorPane; }
    @Override public Set<PaneId> relevantToolWindows() { return Set.of(); }

    @Override
    public void activate(StudioSession session) {
        session.activeEditSession.set(null);
    }

    @Override public void dispose() { editorPane.dispose(); }

    public EigenfieldDocument document() { return document; }
    public EigenfieldEditorPane editorPane() { return editorPane; }
}
