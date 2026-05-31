package ax.xz.mri.ui.workbench;

import ax.xz.mri.project.SubstanceDocument;
import ax.xz.mri.ui.substance.SubstanceEditorPane;
import ax.xz.mri.ui.viewmodel.StudioSession;
import javafx.scene.Node;

/**
 * Editor provider for {@link SubstanceDocument}. Content: the substance
 * editor pane (form + position scatter). The editor fills the document
 * tab on its own.
 */
public final class SubstanceEditorProvider implements DocumentEditorProvider {
    private final SubstanceDocument document;
    private final SubstanceEditorPane editorPane;

    public SubstanceEditorProvider(SubstanceDocument document, StudioSession session,
                                   WorkbenchController controller) {
        this.document = document;
        this.editorPane = new SubstanceEditorPane(
            new PaneContext(session, controller, PaneId.SUBSTANCE_EDITOR), document);
    }

    @Override public Node editorContent() { return editorPane; }

    @Override
    public void activate(StudioSession session) {
        session.activeEditSession.set(null);
    }

    @Override public void dispose() { editorPane.dispose(); }

    public SubstanceDocument document() { return document; }
    public SubstanceEditorPane editorPane() { return editorPane; }
}
