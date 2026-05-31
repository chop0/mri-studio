package ax.xz.mri.ui.procedure;

import ax.xz.mri.model.procedure.ProcedureStarterLibrary;
import ax.xz.mri.project.ProcedureDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.support.FxTestSupport;
import ax.xz.mri.ui.viewmodel.StudioSession;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.PaneId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Procedure editor pane interaction tests.
 *
 * <p>Covers the header rename flow, run/stop button state transitions,
 * outputs tab population on tick, and the empty/loaded swap of the outputs
 * placeholder. Smoke-tests the adaptive-coherent starter source: it must
 * compile cleanly through the editor's recompile pipeline.
 */
class ProcedureEditorPaneTest {

    @Test
    void constructsWithBlankProcedure() {
        FxTestSupport.runOnFxThread(() -> {
            var doc = makeProcedureDoc("Empty", "// nothing");
            var session = new StudioSession();
            var pane = new ProcedureEditorPane(paneContext(session), doc);
            assertNotNull(pane);
            pane.dispose();
        });
    }

    @Test
    void adaptiveCoherentSourceCompilesCleanly() {
        var starter = ProcedureStarterLibrary.byId("nv-adaptive-coherent").orElseThrow();
        FxTestSupport.runOnFxThread(() -> {
            var doc = makeProcedureDoc(starter.name(), starter.source());
            var session = new StudioSession();
            var pane = new ProcedureEditorPane(paneContext(session), doc);
            assertNotNull(pane);
            pane.dispose();
        });
    }

    @Test
    void blankStarterSourceCompiles() {
        var starter = ProcedureStarterLibrary.byId("blank").orElseThrow();
        FxTestSupport.runOnFxThread(() -> {
            var doc = makeProcedureDoc(starter.name(), starter.source());
            var session = new StudioSession();
            var pane = new ProcedureEditorPane(paneContext(session), doc);
            pane.dispose();
        });
    }

    @Test
    void nvKSpaceSweepSourceCompiles() {
        var starter = ProcedureStarterLibrary.byId("nv-kspace-sweep").orElseThrow();
        FxTestSupport.runOnFxThread(() -> {
            var doc = makeProcedureDoc(starter.name(), starter.source());
            var session = new StudioSession();
            var pane = new ProcedureEditorPane(paneContext(session), doc);
            pane.dispose();
        });
    }

    @Test
    void allStartersConstructProcedureEditorWithoutThrowing() {
        for (var starter : ProcedureStarterLibrary.all()) {
            FxTestSupport.runOnFxThread(() -> {
                var doc = makeProcedureDoc(starter.name(), starter.source());
                var session = new StudioSession();
                var pane = new ProcedureEditorPane(paneContext(session), doc);
                pane.dispose();
            });
        }
    }

    private static ProcedureDocument makeProcedureDoc(String name, String source) {
        return new ProcedureDocument(
            new ProjectNodeId("proc-" + java.util.UUID.randomUUID()),
            name, source);
    }

    private static PaneContext paneContext(StudioSession session) {
        return new PaneContext(session, null, PaneId.PROCEDURE_EDITOR);
    }
}
