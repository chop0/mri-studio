package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplorerTreeBuilderTest {

    @Test
    void treeShowsEigenfieldsGroup() {
        var repository = ProjectRepository.untitled();
        repository.addEigenfield(new EigenfieldDocument(
            new ProjectNodeId("ef-1"), "B0 Helmholtz", "Main field", "return Vec3.of(0, 0, 1);", "T"));
        repository.addEigenfield(new EigenfieldDocument(
            new ProjectNodeId("ef-2"), "RF Loop", "RF coil", "return Vec3.of(1, 0, 0);", "T"));

        var root = ExplorerTreeBuilder.build(repository);

        var eigenfieldsGroup = root.getChildren().stream()
            .filter(item -> item.getValue().label().equals("Eigenfields"))
            .findFirst()
            .orElse(null);
        assertTrue(eigenfieldsGroup != null);
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
        assertTrue(eigenfieldsGroup == null);
    }
}
