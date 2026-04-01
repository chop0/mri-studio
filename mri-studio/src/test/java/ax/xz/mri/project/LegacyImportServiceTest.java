package ax.xz.mri.project;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyImportServiceTest {
    @Test
    void nonIterativeScenarioBecomesDirectImportedCapture() {
        var bundle = ProjectTestSupport.importBundle();
        var scenario = bundle.nodes().values().stream()
            .filter(ImportedScenarioDocument.class::isInstance)
            .map(ImportedScenarioDocument.class::cast)
            .filter(node -> node.name().equals("Alpha Capture"))
            .findFirst()
            .orElseThrow();

        assertTrue(scenario.directCaptureIds().size() == 1);
        assertNull(scenario.importedRunId());

        var capture = assertInstanceOf(ImportedCaptureDocument.class, bundle.nodes().get(scenario.directCaptureIds().getFirst()));
        assertEquals("Alpha Capture", capture.name());
        assertEquals("0", capture.iterationKey());
        assertNotNull(bundle.nodes().get(capture.sequenceSnapshotId()));
    }

    @Test
    void iterativeScenarioBecomesImportedRunWithBookmarksAndCaptures() {
        var bundle = ProjectTestSupport.importBundle();
        var scenario = bundle.nodes().values().stream()
            .filter(ImportedScenarioDocument.class::isInstance)
            .map(ImportedScenarioDocument.class::cast)
            .filter(node -> node.name().equals("Beta Run"))
            .findFirst()
            .orElseThrow();
        var run = assertInstanceOf(ImportedOptimisationRunDocument.class, bundle.nodes().get(scenario.importedRunId()));

        assertEquals(3, run.captureIds().size());
        assertEquals(run.captureIds().getFirst(), run.firstCaptureId());
        assertEquals(run.captureIds().getLast(), run.latestCaptureId());

        var bookmarks = bundle.children().get(run.id()).stream()
            .map(bundle.nodes()::get)
            .map(RunBookmarkDocument.class::cast)
            .toList();
        assertEquals(2, bookmarks.size());
        assertEquals(BookmarkKind.FIRST, bookmarks.getFirst().bookmarkKind());
        assertEquals(BookmarkKind.LAST, bookmarks.getLast().bookmarkKind());
    }
}
