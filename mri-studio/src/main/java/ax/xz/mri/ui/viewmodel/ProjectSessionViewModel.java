package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.project.*;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Project-first selection, import, run navigation, and snapshot-promotion state. */
public final class ProjectSessionViewModel {
    public final ObjectProperty<ProjectRepository> repository = new SimpleObjectProperty<>(ProjectRepository.untitled());
    public final ObjectProperty<Path> projectRoot = new SimpleObjectProperty<>();
    public final ExplorerTreeViewModel explorer = new ExplorerTreeViewModel();
    public final WorkspaceSelectionViewModel workspace = new WorkspaceSelectionViewModel();
    public final InspectorViewModel inspector = new InspectorViewModel();
    public final ActiveCaptureViewModel activeCapture = new ActiveCaptureViewModel();
    public final RunNavigationViewModel runNavigation = new RunNavigationViewModel();

    private final LegacyImportService importService = new LegacyImportService();
    private final ProjectSerialiser serialiser = new ProjectSerialiser();

    public ProjectSessionViewModel() {
        explorer.repository.set(repository.get());
        repository.addListener((obs, oldValue, newValue) -> {
            explorer.repository.set(newValue);
            explorer.refresh();
        });
        runNavigation.activeCaptureId.addListener((obs, oldValue, newValue) -> {
            if (newValue == null) {
                if (runNavigation.activeRunId.get() != null) {
                    activeCapture.activeCapture.set(null);
                }
                return;
            }
            activeCapture.activeCapture.set(repository.get().resolveCapture(newValue));
        });
    }

    public void openImport(File file) throws IOException {
        var bundle = importService.importFile(file);
        openImportedBundle(bundle);
    }

    public void openImportedBundle(ImportedProjectBundle bundle) {
        repository.get().replaceImportBundle(bundle);
        explorer.refresh();
        selectNode(bundle.importLink().id());
        openNode(repository.get().firstOpenableNodeForImport(bundle.importLink().id()));
    }

    public void saveProject(Path root) throws IOException {
        Files.createDirectories(root);
        projectRoot.set(root.toAbsolutePath().normalize());

        var repo = repository.get();
        if ("Untitled Project".equals(repo.manifest().name())) {
            repo.setManifest(new ProjectManifest(root.getFileName().toString(), repo.manifest().layoutFile(), repo.manifest().uiStateFile()));
        }

        serialiser.writeManifest(root.resolve("mri-project.toml"), repo.manifest());
        for (var importId : repo.importLinkIds()) {
            var link = (ImportLinkDocument) repo.node(importId);
            String slug = slug(link.name());
            serialiser.writeImportLink(root.resolve("imports").resolve(slug + ".import.toml"), link);
            var index = repo.importIndex(importId);
            if (index != null) {
                serialiser.writeJson(root.resolve("imports").resolve(slug + ".index.json"), index);
            }
        }
        for (var sequenceId : repo.sequenceIds()) {
            var sequence = (SequenceDocument) repo.node(sequenceId);
            serialiser.writeJson(root.resolve("sequences").resolve(slug(sequence.name())).resolve("sequence.json"), sequence);
        }
        explorer.refresh();
    }

    public void openProject(Path root) throws IOException {
        var manifest = serialiser.readManifest(root.resolve("mri-project.toml"));
        var loadedRepository = new ProjectRepository(manifest);

        var importsDir = root.resolve("imports");
        if (Files.isDirectory(importsDir)) {
            try (var files = Files.list(importsDir)) {
                for (var path : files.filter(candidate -> candidate.getFileName().toString().endsWith(".import.toml")).sorted().toList()) {
                    var link = serialiser.readImportLink(path);
                    File source = new File(link.sourcePath());
                    if (source.isFile()) {
                        loadedRepository.replaceImportBundle(importService.importFile(source));
                    }
                }
            }
        }

        var sequencesDir = root.resolve("sequences");
        if (Files.isDirectory(sequencesDir)) {
            try (var files = Files.walk(sequencesDir)) {
                for (var path : files.filter(candidate -> candidate.getFileName().toString().equals("sequence.json")).toList()) {
                    loadedRepository.addSequence(serialiser.readJson(path, SequenceDocument.class));
                }
            }
        }

        repository.set(loadedRepository);
        projectRoot.set(root.toAbsolutePath().normalize());
        workspace.activeNodeId.set(null);
        inspector.inspectedNodeId.set(null);
        runNavigation.clear();
        activeCapture.activeCapture.set(null);
        explorer.refresh();
        if (!loadedRepository.importLinkIds().isEmpty()) {
            openNode(loadedRepository.firstOpenableNodeForImport(loadedRepository.importLinkIds().getFirst()));
        } else if (!loadedRepository.sequenceIds().isEmpty()) {
            openNode(loadedRepository.sequenceIds().getFirst());
        }
    }

    public void reloadSelectedImport() throws IOException {
        ProjectNodeId selected = Optional.ofNullable(inspector.inspectedNodeId.get())
            .orElse(workspace.activeNodeId.get());
        if (selected == null) return;
        ProjectNodeId importId = repository.get().containingImport(selected);
        if (importId == null) return;
        var node = repository.get().node(importId);
        if (!(node instanceof ImportLinkDocument link)) return;
        openImport(new File(link.sourcePath()));
    }

    public void selectNode(ProjectNodeId nodeId) {
        explorer.selectedNodeId.set(nodeId);
        inspector.inspectedNodeId.set(nodeId);
    }

    public void openNode(ProjectNodeId nodeId) {
        if (nodeId == null) return;
        var repo = repository.get();
        var node = repo.node(nodeId);
        if (node == null) return;

        switch (node) {
            case RunBookmarkDocument bookmark -> {
                runNavigation.openRun(repo, bookmark.runId(), bookmark.targetCaptureId());
                var visibleOwner = visibleRunOwner(repo, bookmark.runId());
                workspace.activeNodeId.set(visibleOwner);
                inspector.inspectedNodeId.set(visibleOwner);
                syncActiveCaptureFromRun();
            }
            case ax.xz.mri.project.ImportedScenarioDocument scenario -> {
                if (scenario.iterative() && scenario.importedRunId() != null) {
                    runNavigation.openRun(repo, scenario.importedRunId(), null);
                    workspace.activeNodeId.set(nodeId);
                    inspector.inspectedNodeId.set(nodeId);
                    syncActiveCaptureFromRun();
                } else if (!scenario.directCaptureIds().isEmpty()) {
                    workspace.activeNodeId.set(nodeId);
                    inspector.inspectedNodeId.set(nodeId);
                    activeCapture.activeCapture.set(repo.resolveCapture(scenario.directCaptureIds().getFirst()));
                } else {
                    workspace.activeNodeId.set(nodeId);
                    inspector.inspectedNodeId.set(nodeId);
                    activeCapture.activeCapture.set(null);
                }
            }
            case ax.xz.mri.project.ImportedOptimisationRunDocument ignored -> {
                runNavigation.openRun(repo, nodeId, null);
                workspace.activeNodeId.set(nodeId);
                inspector.inspectedNodeId.set(nodeId);
                syncActiveCaptureFromRun();
            }
            case OptimisationRunDocument ignored -> {
                runNavigation.openRun(repo, nodeId, null);
                workspace.activeNodeId.set(nodeId);
                inspector.inspectedNodeId.set(nodeId);
                syncActiveCaptureFromRun();
            }
            case ImportedCaptureDocument _, CaptureDocument _ -> {
                runNavigation.clear();
                workspace.activeNodeId.set(nodeId);
                inspector.inspectedNodeId.set(nodeId);
                activeCapture.activeCapture.set(repo.resolveCapture(nodeId));
            }
            case ax.xz.mri.project.SequenceSnapshotDocument _ -> {
                workspace.activeNodeId.set(nodeId);
                inspector.inspectedNodeId.set(nodeId);
                runNavigation.clear();
                activeCapture.activeCapture.set(null);
            }
            case ax.xz.mri.project.SimulationDocument simulation -> openNode(simulation.captureId());
            case ax.xz.mri.project.ImportLinkDocument ignored -> {
                workspace.activeNodeId.set(nodeId);
                inspector.inspectedNodeId.set(nodeId);
                activeCapture.activeCapture.set(null);
            }
            default -> {
                runNavigation.clear();
                workspace.activeNodeId.set(nodeId);
                inspector.inspectedNodeId.set(nodeId);
                activeCapture.activeCapture.set(null);
            }
        }
    }

    public void promoteSelectedSnapshotToSequence() {
        promoteSnapshotForNode(inspector.inspectedNodeId.get());
    }

    public void promoteActiveSnapshotToSequence() {
        if (workspace.activeNodeId.get() != null) {
            promoteSnapshotForNode(workspace.activeNodeId.get());
            return;
        }
        promoteSnapshotForNode(inspector.inspectedNodeId.get());
    }

    public void seekRunCapture(ProjectNodeId captureId) {
        runNavigation.seekCapture(captureId);
        syncActiveCaptureFromRun();
    }

    private void promoteSnapshotForNode(ProjectNodeId nodeId) {
        if (nodeId == null) return;
        var repo = repository.get();
        var snapshot = repo.resolveSnapshot(nodeId, runNavigation.activeCaptureId.get());
        if (snapshot == null) return;
        String baseName = snapshot.name().replace("Snapshot", "").trim();
        var sequence = repo.promoteSnapshotToSequence(snapshot.id(), baseName.isBlank() ? "Imported Sequence" : baseName + " Sequence");
        explorer.refresh();
        selectNode(sequence.id());
        openNode(sequence.id());
    }

    private void syncActiveCaptureFromRun() {
        if (runNavigation.activeCaptureId.get() == null) {
            activeCapture.activeCapture.set(null);
            return;
        }
        activeCapture.activeCapture.set(repository.get().resolveCapture(runNavigation.activeCaptureId.get()));
    }

    private static ProjectNodeId visibleRunOwner(ProjectRepository repository, ProjectNodeId runId) {
        var parent = repository.parentOf(runId);
        return repository.node(runId) instanceof ImportedOptimisationRunDocument && parent != null ? parent : runId;
    }

    private static String slug(String value) {
        String collapsed = value == null ? "untitled" : value.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-");
        collapsed = collapsed.replaceAll("^-+", "").replaceAll("-+$", "");
        return collapsed.isBlank() ? "untitled" : collapsed;
    }
}
