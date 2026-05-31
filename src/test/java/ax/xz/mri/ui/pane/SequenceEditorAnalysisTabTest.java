package ax.xz.mri.ui.pane;

import ax.xz.mri.support.FxTestSupport;
import ax.xz.mri.ui.viewmodel.StudioSession;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.PaneId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link SequenceEditorPane#selectAnalysisTab} actually flips
 * the underlying JavaFX TabPane to the requested PaneId. This was the bug
 * the user pushed back on: the controller's focusPane fired correctly but
 * the analysis sub-tabs live inside a plain JavaFX TabPane, not in a Bento
 * dockable, so nothing happened.
 */
class SequenceEditorAnalysisTabTest {

    @Test
    void selectAnalysisTabSwitchesToSphere() {
        FxTestSupport.runOnFxThread(() -> {
            var pane = freshPane();
            assertTrue(pane.selectAnalysisTab(PaneId.SPHERE));
        });
    }

    @Test
    void selectAnalysisTabSwitchesToCrossSection() {
        FxTestSupport.runOnFxThread(() -> {
            var pane = freshPane();
            assertTrue(pane.selectAnalysisTab(PaneId.CROSS_SECTION));
        });
    }

    @Test
    void selectAnalysisTabSwitchesToTraces() {
        FxTestSupport.runOnFxThread(() -> {
            var pane = freshPane();
            assertTrue(pane.selectAnalysisTab(PaneId.TRACE_PHASE));
            assertTrue(pane.selectAnalysisTab(PaneId.TRACE_POLAR));
            assertTrue(pane.selectAnalysisTab(PaneId.TRACE_MAGNITUDE));
        });
    }

    @Test
    void selectAnalysisTabReturnsFalseForUnknownPaneId() {
        FxTestSupport.runOnFxThread(() -> {
            var pane = freshPane();
            // SEQUENCE_EDITOR isn't an analysis sub-tab — the call must
            // report "no, I don't host this" so the controller can fall
            // through to the normal Bento path.
            assertFalse(pane.selectAnalysisTab(PaneId.SEQUENCE_EDITOR));
            assertFalse(pane.selectAnalysisTab(PaneId.EXPLORER));
            assertFalse(pane.selectAnalysisTab(PaneId.INSPECTOR));
            assertFalse(pane.selectAnalysisTab(PaneId.POINTS));
            assertFalse(pane.selectAnalysisTab(PaneId.MESSAGES));
            assertFalse(pane.selectAnalysisTab(PaneId.SIM_CONFIG_EDITOR));
            assertFalse(pane.selectAnalysisTab(PaneId.SUBSTANCE_EDITOR));
            assertFalse(pane.selectAnalysisTab(PaneId.PROCEDURE_EDITOR));
            assertFalse(pane.selectAnalysisTab(PaneId.EIGENFIELD_EDITOR));
        });
    }

    @Test
    void selectAnalysisTabRepeatedlyIsIdempotent() {
        FxTestSupport.runOnFxThread(() -> {
            var pane = freshPane();
            for (int i = 0; i < 5; i++) {
                assertTrue(pane.selectAnalysisTab(PaneId.CROSS_SECTION));
                assertTrue(pane.selectAnalysisTab(PaneId.SPHERE));
            }
        });
    }

    private static SequenceEditorPane freshPane() {
        var session = new StudioSession();
        var ctx = new PaneContext(session, null, PaneId.SEQUENCE_EDITOR);
        return new SequenceEditorPane(ctx);
    }
}
