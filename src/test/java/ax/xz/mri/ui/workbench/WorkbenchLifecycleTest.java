package ax.xz.mri.ui.workbench;

import ax.xz.mri.support.FxTestSupport;
import ax.xz.mri.ui.viewmodel.StudioSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class WorkbenchLifecycleTest {
    @Test
    void controllerConstructsAllWorkbenchPanesAndDisposesCleanly() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new StudioSession();
            var controller = new WorkbenchController(session);

            controller.activatePane(PaneId.SPHERE);

            assertNotNull(controller.dockRoot());
            assertNotNull(controller.commandRegistry().lookup(CommandId.RESET_LAYOUT).orElse(null));

            controller.dispose();
            session.dispose();
        });
    }
}
