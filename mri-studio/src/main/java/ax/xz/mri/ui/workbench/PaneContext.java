package ax.xz.mri.ui.workbench;

import ax.xz.mri.ui.viewmodel.StudioSession;

/** Shared workbench services available to every pane. */
public class PaneContext {
    private final StudioSession session;
    private final WorkbenchController controller;
    private final PaneId paneId;

    public PaneContext(StudioSession session, WorkbenchController controller, PaneId paneId) {
        this.session = session;
        this.controller = controller;
        this.paneId = paneId;
    }

    public StudioSession session() {
        return session;
    }

    public WorkbenchController controller() {
        return controller;
    }

    public PaneId paneId() {
        return paneId;
    }

    public void activate() {
        controller.activatePane(paneId);
    }

    public void publishStatus(String text) {
        controller.setPaneStatus(paneId, text);
    }

    public void floatPane() {
        controller.floatPane(paneId);
    }

    public void dockPane() {
        controller.dockPane(paneId);
    }

    public void focusPane() {
        controller.focusPane(paneId);
    }

    public boolean isFloating() {
        return controller.isFloating(paneId);
    }
}
