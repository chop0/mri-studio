package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
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
        // Empty placeholder folders (Simulations, Optimisations) are hidden when they have no children.
        assertTrue(sequences.getChildren().getFirst().getChildren().isEmpty());
    }

    @Test
    void treeShowsEigenfieldsGroup() {
        var repository = ProjectRepository.untitled();
        repository.addEigenfield(new EigenfieldDocument(
            new ProjectNodeId("ef-1"), "B0 Helmholtz", "Main field", "return Vec3.of(0, 0, 1);", "T", 1.0));
        repository.addEigenfield(new EigenfieldDocument(
            new ProjectNodeId("ef-2"), "RF Loop", "RF coil", "return Vec3.of(1, 0, 0);", "T", 1.0));

        var root = ExplorerTreeBuilder.build(repository);

        // Eigenfields group should appear after Imports and Sequences (and possibly Simulation Configs)
        var eigenfieldsGroup = root.getChildren().stream()
            .filter(item -> item.getValue().label().equals("Eigenfields"))
            .findFirst()
            .orElse(null);
        assertTrue(eigenfieldsGroup != null, "Should have an Eigenfields group");
        assertEquals(2, eigenfieldsGroup.getChildren().size());
        assertTrue(eigenfieldsGroup.getChildren().stream()
            .anyMatch(item -> item.getValue().label().contains("B0 Helmholtz")));
        assertTrue(eigenfieldsGroup.getChildren().stream()
            .anyMatch(item -> item.getValue().label().contains("RF Loop")));
    }

    @Test
    void treeHidesEigenfieldsGroupWhenEmpty() {
        var repository = ProjectRepository.untitled();
        var root = ExplorerTreeBuilder.build(repository);

        var eigenfieldsGroup = root.getChildren().stream()
            .filter(item -> item.getValue().label().equals("Eigenfields"))
            .findFirst()
            .orElse(null);
        assertTrue(eigenfieldsGroup == null, "Eigenfields group should be hidden when empty");
    }
}
