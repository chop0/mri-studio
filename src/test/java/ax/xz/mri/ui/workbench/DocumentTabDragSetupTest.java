package ax.xz.mri.ui.workbench;

import ax.xz.mri.support.FxTestSupport;
import ax.xz.mri.ui.viewmodel.StudioSession;
import javafx.scene.Parent;
import org.junit.jupiter.api.Test;
import software.coley.bentofx.control.Header;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins BentoFX document-tab drag/drop wiring at runtime, not just config.
 *
 * <p>The earlier version of this test only checked
 * {@code dockable.isCanBeDragged()} — the {@link Header} factory in Bento
 * 0.10.1 already calls {@code Header.withDragDrop()} by default, so that
 * check passed even when the user couldn't actually drag a tab. The real
 * runtime requirement is {@code bento.registerRoot(rootBranch)} — without
 * it Bento's {@code getRootContainers()} list is empty and drag-target
 * detection finds nothing to drop onto, making every drag a silent no-op.
 *
 * <p>This test pins:
 * <ol>
 *   <li>{@code bento.getRootContainers()} contains the workbench root.</li>
 *   <li>Document-tab Header controls exist with {@code canBeDragged=true} and
 *       a positive {@code dragGroup}.</li>
 * </ol>
 */
class DocumentTabDragSetupTest {

    @Test
    void documentTabsAreConfiguredForDragAndDrop() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new StudioSession();
            var controller = new WorkbenchController(session);
            controller.loadLayoutFromStore();

            var emptyClipSeq = new ax.xz.mri.model.sequence.ClipSequence(
                10.0, 1000.0, List.of(), List.of());
            controller.openSequenceTab(new ax.xz.mri.project.SequenceDocument(
                new ax.xz.mri.project.ProjectNodeId("a"), "Seq A", emptyClipSeq, null));
            controller.openSequenceTab(new ax.xz.mri.project.SequenceDocument(
                new ax.xz.mri.project.ProjectNodeId("b"), "Seq B", emptyClipSeq, null));

            var root = (Parent) controller.dockRoot();
            new javafx.scene.Scene(new javafx.scene.layout.StackPane(root), 800, 600);
            root.applyCss();
            root.layout();

            var bento = controller.bentoForTesting();
            assertFalse(bento.getRootContainers().isEmpty(),
                "bento.registerRoot was never called — drop-target detection is dead.");

            var headers = new ArrayList<Header>();
            collectHeaders(root, headers);
            assertFalse(headers.isEmpty(),
                "No BentoFX Header in the tab strip — drag is structurally impossible.");
            for (var h : headers) {
                var d = h.getDockable();
                assertTrue(d.isCanBeDragged(),
                    "Header '" + d.getIdentifier() + "' canBeDragged=false");
                assertTrue(d.getDragGroup() > 0,
                    "Header '" + d.getIdentifier() + "' dragGroup=" + d.getDragGroup());
            }

            controller.dispose();
            session.dispose();
        });
    }

    private static void collectHeaders(Parent root, List<Header> sink) {
        for (var child : root.getChildrenUnmodifiable()) {
            if (child instanceof Header h) sink.add(h);
            if (child instanceof Parent p) collectHeaders(p, sink);
        }
    }
}
