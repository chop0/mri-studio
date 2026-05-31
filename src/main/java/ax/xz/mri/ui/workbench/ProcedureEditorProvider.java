package ax.xz.mri.ui.workbench;

import ax.xz.mri.project.ProcedureDocument;
import ax.xz.mri.ui.procedure.ProcedureEditorPane;
import ax.xz.mri.ui.viewmodel.StudioSession;
import javafx.scene.Node;

/**
 * Editor provider for {@link ProcedureDocument}. Content: a code editor plus
 * the harness panel (run / pause / stop + tick stream).
 */
public final class ProcedureEditorProvider implements DocumentEditorProvider {
    private final ProcedureDocument document;
    private final ProcedureEditorPane editorPane;

    public ProcedureEditorProvider(ProcedureDocument document, StudioSession session,
                                   WorkbenchController controller) {
        this.document = document;
        this.editorPane = new ProcedureEditorPane(
            new PaneContext(session, controller, PaneId.PROCEDURE_EDITOR), document);
    }

    @Override public Node editorContent() { return editorPane; }

    @Override
    public void activate(StudioSession session) {
        session.activeEditSession.set(null);
    }

    @Override public void dispose() { editorPane.dispose(); }

    public ProcedureDocument document() { return document; }
    public ProcedureEditorPane editorPane() { return editorPane; }
}
