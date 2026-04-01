package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.project.ProjectTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplorerTreeBuilderTest {
    @Test
    void treeShowsImportsRunsBookmarksCapturesAndSequenceRoots() {
        var repository = ProjectRepository.untitled();
        var bundle = ProjectTestSupport.importBundle();
        repository.addImportBundle(bundle);
        var alphaScenario = bundle.nodes().values().stream()
            .filter(ax.xz.mri.project.ImportedScenarioDocument.class::isInstance)
            .map(ax.xz.mri.project.ImportedScenarioDocument.class::cast)
            .filter(node -> !node.iterative())
            .findFirst()
            .orElseThrow();
        repository.promoteSnapshotToSequence(
            repository.resolveSnapshot(alphaScenario.id(), null).id(),
            "Promoted"
        );

        var root = ExplorerTreeBuilder.build(repository);

        assertEquals("Untitled Project", root.getValue().label());
        assertEquals("Imports", root.getChildren().get(0).getValue().label());
        assertEquals("Sequences", root.getChildren().get(1).getValue().label());

        var importLink = root.getChildren().get(0).getChildren().getFirst();
        assertTrue(importLink.getChildren().stream().anyMatch(item -> item.getValue().label().equals("Scenario: Alpha Capture")));
        assertTrue(importLink.getChildren().stream().anyMatch(item -> item.getValue().label().equals("Optimisation: Beta Run")));

        var betaRunScenario = importLink.getChildren().stream()
            .filter(item -> item.getValue().label().equals("Optimisation: Beta Run"))
            .findFirst()
            .orElseThrow();
        assertTrue(betaRunScenario.getChildren().stream().anyMatch(item -> item.getValue().label().equals("First Iteration")));
        assertTrue(betaRunScenario.getChildren().stream().anyMatch(item -> item.getValue().label().equals("Last Iteration")));

        var sequences = root.getChildren().get(1);
        assertEquals("Sequence: Promoted", sequences.getChildren().getFirst().getValue().label());
        assertEquals("Simulations", sequences.getChildren().getFirst().getChildren().get(0).getValue().label());
        assertEquals("Optimisations", sequences.getChildren().getFirst().getChildren().get(1).getValue().label());
    }
}
