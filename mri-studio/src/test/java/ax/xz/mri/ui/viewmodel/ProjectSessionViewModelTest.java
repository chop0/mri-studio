package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.project.BookmarkKind;
import ax.xz.mri.project.ImportedOptimisationRunDocument;
import ax.xz.mri.project.ImportedScenarioDocument;
import ax.xz.mri.project.ProjectTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProjectSessionViewModelTest {
    @Test
    void openingImportedBundleOpensFirstScenarioCaptureAndRunBookmarksSeekParentRun() {
        var session = new ProjectSessionViewModel();
        var bundle = ProjectTestSupport.importBundle();

        session.openImportedBundle(bundle);

        assertEquals("Alpha Capture", session.activeCapture.activeCapture.get().name());
        assertEquals("Alpha Capture", session.activeCapture.activeCapture.get().scenarioName());

        var repository = session.repository.get();
        var run = bundle.nodes().values().stream()
            .filter(ImportedOptimisationRunDocument.class::isInstance)
            .map(ImportedOptimisationRunDocument.class::cast)
            .findFirst()
            .orElseThrow();
        var firstBookmark = repository.bookmarksForRun(run.id()).stream()
            .filter(bookmark -> bookmark.bookmarkKind() == BookmarkKind.FIRST)
            .findFirst()
            .orElseThrow();
        var scenario = bundle.nodes().values().stream()
            .filter(ImportedScenarioDocument.class::isInstance)
            .map(ImportedScenarioDocument.class::cast)
            .filter(node -> node.iterative())
            .findFirst()
            .orElseThrow();

        session.openNode(firstBookmark.id());

        assertEquals(scenario.id(), session.workspace.activeNodeId.get());
        assertEquals(run.firstCaptureId(), session.runNavigation.activeCaptureId.get());
        assertEquals("0", session.activeCapture.activeCapture.get().iterationKey());
    }

    @Test
    void promotingActiveRunSnapshotCreatesSequenceAndClearsActiveCapture() {
        var session = new ProjectSessionViewModel();
        var bundle = ProjectTestSupport.importBundle();
        session.openImportedBundle(bundle);

        var run = bundle.nodes().values().stream()
            .filter(ImportedOptimisationRunDocument.class::isInstance)
            .map(ImportedOptimisationRunDocument.class::cast)
            .findFirst()
            .orElseThrow();
        session.openNode(run.id());
        session.promoteActiveSnapshotToSequence();

        assertEquals(1, session.repository.get().sequenceIds().size());
        var sequenceId = session.repository.get().sequenceIds().getFirst();
        assertEquals(sequenceId, session.workspace.activeNodeId.get());
        assertNull(session.activeCapture.activeCapture.get());
    }

    @Test
    void reopeningRunKeepsPreviouslySelectedIteration() {
        var session = new ProjectSessionViewModel();
        var bundle = ProjectTestSupport.importBundle();
        session.openImportedBundle(bundle);

        var run = bundle.nodes().values().stream()
            .filter(ImportedOptimisationRunDocument.class::isInstance)
            .map(ImportedOptimisationRunDocument.class::cast)
            .findFirst()
            .orElseThrow();

        session.openNode(run.id());
        session.seekRunCapture(run.captureIds().get(1));
        session.openNode(run.id());

        assertEquals(run.captureIds().get(1), session.runNavigation.activeCaptureId.get());
        assertEquals("2", session.activeCapture.activeCapture.get().iterationKey());
    }
}
