package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.project.ProjectNode;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectNodeKind;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.ui.workbench.ProjectDisplayNames;
import ax.xz.mri.ui.workbench.StudioIconKind;
import javafx.scene.control.TreeItem;

/** Builds the semantic explorer tree from the project repository. */
public final class ExplorerTreeBuilder {
    private ExplorerTreeBuilder() {}

    public static TreeItem<ExplorerEntry> build(ProjectRepository repository) {
        var root = branch(repository.manifest().name(), null, StudioIconKind.PROJECT);

        var sequences = branch("Sequences", null, StudioIconKind.GROUP_SEQUENCES);
        for (var sequenceId : repository.sequenceIds()) {
            sequences.getChildren().add(leaf(repository.node(sequenceId), StudioIconKind.SEQUENCE));
        }
        if (!sequences.getChildren().isEmpty()) root.getChildren().add(sequences);

        var simConfigs = branch("Simulation Configs", null, StudioIconKind.SIMULATION);
        for (var configId : repository.simConfigIds()) {
            simConfigs.getChildren().add(leaf(repository.node(configId), StudioIconKind.SIMULATION));
        }
        if (!simConfigs.getChildren().isEmpty()) root.getChildren().add(simConfigs);

        var circuits = branch("Circuits", null, StudioIconKind.SIMULATION);
        for (var circuitId : repository.circuitIds()) {
            circuits.getChildren().add(leaf(repository.node(circuitId), StudioIconKind.SIMULATION));
        }
        if (!circuits.getChildren().isEmpty()) root.getChildren().add(circuits);

        var eigenfields = branch("Eigenfields", null, StudioIconKind.EIGENFIELD);
        for (var eigenfieldId : repository.eigenfieldIds()) {
            eigenfields.getChildren().add(leaf(repository.node(eigenfieldId), StudioIconKind.EIGENFIELD));
        }
        if (!eigenfields.getChildren().isEmpty()) root.getChildren().add(eigenfields);

        root.setExpanded(true);
        sequences.setExpanded(true);
        return root;
    }

    private static TreeItem<ExplorerEntry> leaf(ProjectNode node, StudioIconKind icon) {
        if (node == null) return branch("?", null, icon);
        return branch(ProjectDisplayNames.label(node), node.id(), iconFor(node.kind()));
    }

    private static StudioIconKind iconFor(ProjectNodeKind kind) {
        return switch (kind) {
            case SEQUENCE -> StudioIconKind.SEQUENCE;
            case SIMULATION_CONFIG, CIRCUIT -> StudioIconKind.SIMULATION;
            case EIGENFIELD -> StudioIconKind.EIGENFIELD;
        };
    }

    private static TreeItem<ExplorerEntry> branch(String label, ProjectNodeId nodeId, StudioIconKind iconKind) {
        return new TreeItem<>(new ExplorerEntry(label, nodeId, iconKind, nodeId == null));
    }
}
