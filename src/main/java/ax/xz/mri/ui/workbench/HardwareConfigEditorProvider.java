package ax.xz.mri.ui.workbench;

import ax.xz.mri.project.HardwareConfigDocument;
import ax.xz.mri.ui.viewmodel.StudioSession;
import ax.xz.mri.ui.workbench.pane.HardwareConfigEditorPane;
import javafx.scene.Node;

import java.util.Set;

/**
 * Editor provider for hardware configs. Hosts a full-page
 * {@link HardwareConfigEditorPane} as the workspace tab content; no
 * analysis tool windows are relevant.
 */
public final class HardwareConfigEditorProvider implements DocumentEditorProvider {
    private final HardwareConfigDocument configDoc;
    private final HardwareConfigEditorPane editorPane;

    public HardwareConfigEditorProvider(HardwareConfigDocument configDoc, StudioSession session,
                                        WorkbenchController controller) {
        this.configDoc = configDoc;
        this.editorPane = new HardwareConfigEditorPane(
            new PaneContext(session, controller, PaneId.SIM_CONFIG_EDITOR), configDoc);
    }

    @Override public Node editorContent() { return editorPane; }
    @Override public Set<PaneId> relevantToolWindows() { return Set.of(); }

    @Override
    public void activate(StudioSession session) {
        session.activeEditSession.set(null);
        // Preserve last analysis data — editing a hardware config doesn't replace the run result.
    }

    @Override public boolean isDirty() { return editorPane.isDirty(); }
    @Override public void save() { editorPane.save(); }
    @Override public void dispose() {}

    public HardwareConfigDocument configDoc() { return configDoc; }
    public HardwareConfigEditorPane editorPane() { return editorPane; }
}
