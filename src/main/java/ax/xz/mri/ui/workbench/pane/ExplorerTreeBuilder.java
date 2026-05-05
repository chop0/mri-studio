package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.project.ProjectNode;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectNodeKind;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.ui.workbench.ProjectDisplayNames;
import ax.xz.mri.ui.workbench.StudioIconKind;
import javafx.scene.control.TreeItem;

/** Builds the semantic explorer tree from the project repository. */
public final class ExplorerTreeBuilder {
    private ExplorerTreeBuilder() {}

    public static TreeItem<ExplorerEntry> build(ProjectState repository) {
        var root = branch(repository.manifest().name(), null, StudioIconKind.PROJECT);

        var sequences = branch("Sequences", null, StudioIconKind.GROUP_SEQUENCES);
        for (var sequenceId : repository.sequenceIds()) {
            sequences.getChildren().add(leaf(repository.node(sequenceId), StudioIconKind.SEQUENCE));
        }
        if (!sequences.getChildren().isEmpty()) root.getChildren().add(sequences);

        var simConfigs = branch("Simulation Configs", null, StudioIconKind.SIMULATION);
        for (var configId : repository.simulationIds()) {
            simConfigs.getChildren().add(leaf(repository.node(configId), StudioIconKind.SIMULATION));
        }
        if (!simConfigs.getChildren().isEmpty()) root.getChildren().add(simConfigs);

        var hardwareConfigs = branch("Hardware Configs", null, StudioIconKind.SIMULATION);
        for (var configId : repository.hardwareIds()) {
            hardwareConfigs.getChildren().add(leaf(repository.node(configId), StudioIconKind.SIMULATION));
        }
        if (!hardwareConfigs.getChildren().isEmpty()) root.getChildren().add(hardwareConfigs);

        // Circuits are not a first-class browse target yet: they're always paired
        // with a sim-config, so the user reaches them via the config's editor.

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
            case SIMULATION_CONFIG, CIRCUIT, HARDWARE_CONFIG -> StudioIconKind.SIMULATION;
            case EIGENFIELD -> StudioIconKind.EIGENFIELD;
        };
    }

    private static TreeItem<ExplorerEntry> branch(String label, ProjectNodeId nodeId, StudioIconKind iconKind) {
        return new TreeItem<>(new ExplorerEntry(label, nodeId, iconKind, nodeId == null));
    }
}
