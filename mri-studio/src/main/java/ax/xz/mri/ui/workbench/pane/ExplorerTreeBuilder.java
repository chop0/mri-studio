package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.project.ProjectNode;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectNodeKind;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.project.RunBookmarkDocument;
import ax.xz.mri.project.ImportedScenarioDocument;
import ax.xz.mri.project.ImportedOptimisationRunDocument;
import ax.xz.mri.project.OptimisationRunDocument;
import ax.xz.mri.ui.workbench.ProjectDisplayNames;
import ax.xz.mri.ui.workbench.StudioIconKind;
import javafx.scene.control.TreeItem;

/** Builds the semantic explorer tree from the project repository. */
public final class ExplorerTreeBuilder {
    private ExplorerTreeBuilder() {
    }

    public static TreeItem<ExplorerEntry> build(ProjectRepository repository) {
        var root = branch(repository.manifest().name(), null, StudioIconKind.PROJECT);

        var imports = branch("Imports", null, StudioIconKind.GROUP_IMPORTS);
        for (var importId : repository.importLinkIds()) {
            imports.getChildren().add(buildNode(repository, importId));
        }
        root.getChildren().add(imports);

        var sequences = branch("Sequences", null, StudioIconKind.GROUP_SEQUENCES);
        for (var sequenceId : repository.sequenceIds()) {
            sequences.getChildren().add(buildSequenceNode(repository, sequenceId));
        }
        root.getChildren().add(sequences);

        root.setExpanded(true);
        imports.setExpanded(true);
        sequences.setExpanded(true);
        return root;
    }

    private static TreeItem<ExplorerEntry> buildNode(ProjectRepository repository, ProjectNodeId nodeId) {
        var node = repository.node(nodeId);
        if (node == null) {
            return branch(nodeId.value(), nodeId, StudioIconKind.CAPTURE);
        }
        return switch (node.kind()) {
            case IMPORT_LINK -> buildGenericNode(repository, node);
            case IMPORTED_SCENARIO -> buildImportedScenarioNode(repository, (ImportedScenarioDocument) node);
            case IMPORTED_OPTIMISATION_RUN -> buildRunNode(repository, (ImportedOptimisationRunDocument) node);
            case OPTIMISATION_RUN -> buildRunNode(repository, (OptimisationRunDocument) node);
            case RUN_BOOKMARK -> branch(ProjectDisplayNames.label(node), node.id(), StudioIconKind.BOOKMARK);
            case IMPORTED_CAPTURE, CAPTURE -> branch(ProjectDisplayNames.label(node), node.id(), iconFor(node.kind()));
            case SEQUENCE -> buildSequenceNode(repository, nodeId);
            case SIMULATION, OPTIMISATION_CONFIG -> buildGenericNode(repository, node);
            case SEQUENCE_SNAPSHOT -> null;
        };
    }

    private static TreeItem<ExplorerEntry> buildGenericNode(ProjectRepository repository, ProjectNode node) {
        var item = branch(ProjectDisplayNames.label(node), node.id(), iconFor(node.kind()));
        for (var childId : repository.childrenOf(node.id())) {
            var child = buildNode(repository, childId);
            if (child != null) {
                item.getChildren().add(child);
            }
        }
        return item;
    }

    private static TreeItem<ExplorerEntry> buildImportedScenarioNode(ProjectRepository repository, ImportedScenarioDocument scenario) {
        boolean iterative = scenario.iterative() && scenario.importedRunId() != null;
        var icon = iterative ? StudioIconKind.RUN : StudioIconKind.SCENARIO;
        var label = iterative
            ? "Optimisation: " + scenario.name()
            : ProjectDisplayNames.label(scenario);
        var item = branch(label, scenario.id(), icon);
        if (iterative) {
            var runNode = repository.node(scenario.importedRunId());
            if (runNode instanceof ImportedOptimisationRunDocument run) {
                for (var childId : repository.childrenOf(run.id())) {
                    var child = buildNode(repository, childId);
                    if (child != null) item.getChildren().add(child);
                }
            }
        }
        return item;
    }

    private static TreeItem<ExplorerEntry> buildRunNode(ProjectRepository repository, ImportedOptimisationRunDocument run) {
        var item = branch(ProjectDisplayNames.label(run), run.id(), StudioIconKind.RUN);
        for (var childId : repository.childrenOf(run.id())) {
            var child = buildNode(repository, childId);
            if (child != null) item.getChildren().add(child);
        }
        return item;
    }

    private static TreeItem<ExplorerEntry> buildRunNode(ProjectRepository repository, OptimisationRunDocument run) {
        var item = branch(ProjectDisplayNames.label(run), run.id(), StudioIconKind.RUN);
        for (var childId : repository.childrenOf(run.id())) {
            var child = buildNode(repository, childId);
            if (child != null) item.getChildren().add(child);
        }
        return item;
    }

    private static TreeItem<ExplorerEntry> buildSequenceNode(ProjectRepository repository, ProjectNodeId sequenceId) {
        var item = branch(ProjectDisplayNames.label(repository.node(sequenceId)), sequenceId, StudioIconKind.SEQUENCE);
        var simulations = branch("Simulations", null, StudioIconKind.SIMULATION);
        var optimisations = branch("Optimisations", null, StudioIconKind.OPTIMISATION_CONFIG);
        for (var childId : repository.childrenOf(sequenceId)) {
            var child = repository.node(childId);
            if (child == null) continue;
            if (child.kind() == ProjectNodeKind.SIMULATION) simulations.getChildren().add(buildNode(repository, childId));
            if (child.kind() == ProjectNodeKind.OPTIMISATION_CONFIG) optimisations.getChildren().add(buildNode(repository, childId));
        }
        if (!simulations.getChildren().isEmpty()) item.getChildren().add(simulations);
        if (!optimisations.getChildren().isEmpty()) item.getChildren().add(optimisations);
        item.setExpanded(true);
        return item;
    }

    private static StudioIconKind iconFor(ProjectNodeKind kind) {
        return switch (kind) {
            case IMPORT_LINK -> StudioIconKind.IMPORT;
            case IMPORTED_SCENARIO -> StudioIconKind.SCENARIO;
            case IMPORTED_OPTIMISATION_RUN, OPTIMISATION_RUN -> StudioIconKind.RUN;
            case IMPORTED_CAPTURE, CAPTURE -> StudioIconKind.CAPTURE;
            case SEQUENCE_SNAPSHOT -> StudioIconKind.SNAPSHOT;
            case SEQUENCE -> StudioIconKind.SEQUENCE;
            case SIMULATION -> StudioIconKind.SIMULATION;
            case OPTIMISATION_CONFIG -> StudioIconKind.OPTIMISATION_CONFIG;
            case RUN_BOOKMARK -> StudioIconKind.BOOKMARK;
        };
    }

    private static TreeItem<ExplorerEntry> branch(String label, ProjectNodeId nodeId, StudioIconKind iconKind) {
        return new TreeItem<>(new ExplorerEntry(label, nodeId, iconKind, nodeId == null));
    }
}
