package ax.xz.mri.project;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRepositoryTest {
    @Test
    void repositoryResolvesImportedCapturesAndPromotesSnapshotsToSequences() {
        var bundle = ProjectTestSupport.importBundle();
        var repository = ProjectRepository.untitled();
        repository.addImportBundle(bundle);

        var alphaScenario = bundle.nodes().values().stream()
            .filter(ImportedScenarioDocument.class::isInstance)
            .map(ImportedScenarioDocument.class::cast)
            .filter(node -> node.name().equals("Alpha Capture"))
            .findFirst()
            .orElseThrow();
        var captureId = alphaScenario.directCaptureIds().getFirst();
        var activeCapture = repository.resolveCapture(captureId);

        assertNotNull(activeCapture);
        assertEquals("Alpha Capture", activeCapture.name());
        assertEquals("Alpha Capture", activeCapture.scenarioName());
        assertEquals("0", activeCapture.iterationKey());

        var snapshot = repository.resolveSnapshot(captureId, null);
        var sequence = repository.promoteSnapshotToSequence(snapshot.id(), "Promoted Alpha");
        assertEquals("Promoted Alpha", sequence.name());
        assertTrue(repository.sequenceIds().contains(sequence.id()));
        assertEquals(sequence, repository.node(sequence.id()));
    }

    @Test
    void repositoryDefaultsRunsToLatestCaptureAndExposesBookmarks() {
        var bundle = ProjectTestSupport.importBundle();
        var repository = ProjectRepository.untitled();
        repository.addImportBundle(bundle);

        var run = bundle.nodes().values().stream()
            .filter(ImportedOptimisationRunDocument.class::isInstance)
            .map(ImportedOptimisationRunDocument.class::cast)
            .findFirst()
            .orElseThrow();

        assertEquals(run.latestCaptureId(), repository.defaultCaptureForRun(run.id()));
        assertEquals(3, repository.captureIdsForRun(run.id()).size());
        assertEquals(2, repository.bookmarksForRun(run.id()).size());
        assertEquals("Last Iteration", repository.bookmarksForRun(run.id()).getLast().name());
    }
}
