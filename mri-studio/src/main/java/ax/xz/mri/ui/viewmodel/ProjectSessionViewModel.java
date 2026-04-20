package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.project.CaptureDocument;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ImportLinkDocument;
import ax.xz.mri.project.ImportedCaptureDocument;
import ax.xz.mri.project.ImportedOptimisationRunDocument;
import ax.xz.mri.project.ImportedProjectBundle;
import ax.xz.mri.project.ImportedScenarioDocument;
import ax.xz.mri.project.LegacyImportService;
import ax.xz.mri.project.OptimisationRunDocument;
import ax.xz.mri.project.ProjectManifest;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.project.ProjectSerialiser;
import ax.xz.mri.project.RunBookmarkDocument;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.project.SequenceSnapshotDocument;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.project.SimulationDocument;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    /** Callbacks invoked when nodes are opened (set by WorkbenchController to create tabs). */
    private java.util.function.Consumer<SimulationConfigDocument> onSimConfigOpened;
    private java.util.function.Consumer<SequenceDocument> onSequenceOpened;
    private java.util.function.BiConsumer<ProjectNodeId, ax.xz.mri.project.ActiveCapture> onCaptureOpened;
    private java.util.function.Consumer<EigenfieldDocument> onEigenfieldOpened;

    public void setOnSimConfigOpened(java.util.function.Consumer<SimulationConfigDocument> callback) {
        this.onSimConfigOpened = callback;
    }
    public void setOnSequenceOpened(java.util.function.Consumer<SequenceDocument> callback) {
        this.onSequenceOpened = callback;
    }
    public void setOnCaptureOpened(java.util.function.BiConsumer<ProjectNodeId, ax.xz.mri.project.ActiveCapture> callback) {
        this.onCaptureOpened = callback;
    }
    public void setOnEigenfieldOpened(java.util.function.Consumer<EigenfieldDocument> callback) {
        this.onEigenfieldOpened = callback;
    }

    public ProjectSessionViewModel() {
        repository.addListener((obs, oldValue, newValue) -> explorer.refresh());
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
        // Write current sequences and clean up deleted ones
        var currentSequenceSlugs = new java.util.HashSet<String>();
        for (var sequenceId : repo.sequenceIds()) {
            var sequence = (SequenceDocument) repo.node(sequenceId);
            String seqSlug = slug(sequence.name());
            currentSequenceSlugs.add(seqSlug);
            serialiser.writeJson(root.resolve("sequences").resolve(seqSlug).resolve("sequence.json"), sequence);
        }
        cleanupDeletedDirs(root.resolve("sequences"), currentSequenceSlugs);

        // Write current sim configs and clean up deleted ones
        var currentSimSlugs = new java.util.HashSet<String>();
        for (var configId : repo.simConfigIds()) {
            var config = (SimulationConfigDocument) repo.node(configId);
            String simSlug = slug(config.name());
            currentSimSlugs.add(simSlug);
            serialiser.writeJson(root.resolve("simulations").resolve(simSlug).resolve("config.json"), config);
        }
        cleanupDeletedDirs(root.resolve("simulations"), currentSimSlugs);

        // Write current eigenfields and clean up deleted ones
        var currentEigenfieldSlugs = new java.util.HashSet<String>();
        for (var eigenfieldId : repo.eigenfieldIds()) {
            var eigenfield = (EigenfieldDocument) repo.node(eigenfieldId);
            String efSlug = slug(eigenfield.name());
            currentEigenfieldSlugs.add(efSlug);
            serialiser.writeJson(root.resolve("eigenfields").resolve(efSlug).resolve("eigenfield.json"), eigenfield);
        }
        cleanupDeletedDirs(root.resolve("eigenfields"), currentEigenfieldSlugs);

        explorer.refresh();
    }

    /** Remove directories that no longer correspond to active project nodes. */
    private static void cleanupDeletedDirs(Path parentDir, java.util.Set<String> activeSlugs) throws IOException {
        if (!Files.isDirectory(parentDir)) return;
        try (var dirs = Files.list(parentDir)) {
            for (var dir : dirs.filter(Files::isDirectory).toList()) {
                if (!activeSlugs.contains(dir.getFileName().toString())) {
                    // Recursively delete the orphaned directory
                    try (var walk = Files.walk(dir)) {
                        walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                            try { Files.delete(p); } catch (IOException ignored) {}
                        });
                    }
                }
            }
        }
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

        var simulationsDir = root.resolve("simulations");
        if (Files.isDirectory(simulationsDir)) {
            try (var files = Files.walk(simulationsDir)) {
                for (var path : files.filter(candidate -> candidate.getFileName().toString().equals("config.json")).toList()) {
                    loadedRepository.addSimConfig(serialiser.readJson(path, SimulationConfigDocument.class));
                }
            }
        }

        var eigenfieldsDir = root.resolve("eigenfields");
        if (Files.isDirectory(eigenfieldsDir)) {
            try (var files = Files.walk(eigenfieldsDir)) {
                for (var path : files.filter(candidate -> candidate.getFileName().toString().equals("eigenfield.json")).toList()) {
                    loadedRepository.addEigenfield(serialiser.readJson(path, EigenfieldDocument.class));
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

    public void reloadImport(ProjectNodeId importLinkId) throws IOException {
        var node = repository.get().node(importLinkId);
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
                notifyCaptureOpened(visibleOwner);
            }
            case ImportedScenarioDocument scenario -> {
                if (scenario.iterative() && scenario.importedRunId() != null) {
                    runNavigation.openRun(repo, scenario.importedRunId(), null);
                    workspace.activeNodeId.set(nodeId);
                    inspector.inspectedNodeId.set(nodeId);
                    syncActiveCaptureFromRun();
                    notifyCaptureOpened(nodeId);
                } else if (!scenario.directCaptureIds().isEmpty()) {
                    workspace.activeNodeId.set(nodeId);
                    inspector.inspectedNodeId.set(nodeId);
                    activeCapture.activeCapture.set(repo.resolveCapture(scenario.directCaptureIds().getFirst()));
                    notifyCaptureOpened(nodeId);
                } else {
                    workspace.activeNodeId.set(nodeId);
                    inspector.inspectedNodeId.set(nodeId);
                    activeCapture.activeCapture.set(null);
                }
            }
            case ImportedOptimisationRunDocument _ -> {
                runNavigation.openRun(repo, nodeId, null);
                workspace.activeNodeId.set(nodeId);
                inspector.inspectedNodeId.set(nodeId);
                syncActiveCaptureFromRun();
                notifyCaptureOpened(nodeId);
            }
            case OptimisationRunDocument _ -> {
                runNavigation.openRun(repo, nodeId, null);
                workspace.activeNodeId.set(nodeId);
                inspector.inspectedNodeId.set(nodeId);
                syncActiveCaptureFromRun();
                notifyCaptureOpened(nodeId);
            }
            case ImportedCaptureDocument _, CaptureDocument _ -> {
                runNavigation.clear();
                workspace.activeNodeId.set(nodeId);
                inspector.inspectedNodeId.set(nodeId);
                activeCapture.activeCapture.set(repo.resolveCapture(nodeId));
                notifyCaptureOpened(nodeId);
            }
            case SequenceSnapshotDocument _ -> {
                workspace.activeNodeId.set(nodeId);
                inspector.inspectedNodeId.set(nodeId);
                runNavigation.clear();
                activeCapture.activeCapture.set(null);
            }
            case SequenceDocument seq -> {
                runNavigation.clear();
                workspace.activeNodeId.set(nodeId);
                inspector.inspectedNodeId.set(nodeId);
                activeCapture.activeCapture.set(null);
                if (onSequenceOpened != null) onSequenceOpened.accept(seq);
            }
            case SimulationConfigDocument simConfig -> {
                // Set inspector to show the sim config. The WorkbenchController
                // will handle opening the editor tab via onSimConfigOpened.
                inspector.inspectedNodeId.set(nodeId);
                if (onSimConfigOpened != null) {
                    onSimConfigOpened.accept(simConfig);
                }
            }
            case EigenfieldDocument eigen -> {
                inspector.inspectedNodeId.set(nodeId);
                if (onEigenfieldOpened != null) onEigenfieldOpened.accept(eigen);
            }
            case SimulationDocument simulation -> openNode(simulation.captureId());
            case ImportLinkDocument _ -> {
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

    public void renameSequence(ProjectNodeId sequenceId, String newName) {
        repository.get().renameSequence(sequenceId, newName);
        explorer.refresh();
        selectNode(sequenceId);
        saveProjectQuietly();
    }

    public void deleteSequence(ProjectNodeId sequenceId) {
        repository.get().removeSequence(sequenceId);
        // If the deleted sequence was the active workspace object, clear state.
        if (sequenceId.equals(workspace.activeNodeId.get())) {
            workspace.activeNodeId.set(null);
            activeCapture.activeCapture.set(null);
            runNavigation.clear();
        }
        if (sequenceId.equals(inspector.inspectedNodeId.get())) {
            inspector.inspectedNodeId.set(null);
        }
        explorer.refresh();
        saveProjectQuietly();
    }

    public void deleteSimConfig(ProjectNodeId configId) {
        repository.get().removeSimConfig(configId);
        if (configId.equals(inspector.inspectedNodeId.get())) {
            inspector.inspectedNodeId.set(null);
        }
        explorer.refresh();
        saveProjectQuietly();
    }

    public void renameSimConfig(ProjectNodeId configId, String newName) {
        repository.get().renameSimConfig(configId, newName);
        explorer.refresh();
        selectNode(configId);
        saveProjectQuietly();
    }

    public void deleteEigenfield(ProjectNodeId eigenfieldId) {
        repository.get().removeEigenfield(eigenfieldId);
        if (eigenfieldId.equals(inspector.inspectedNodeId.get())) {
            inspector.inspectedNodeId.set(null);
        }
        explorer.refresh();
        saveProjectQuietly();
    }

    public void renameEigenfield(ProjectNodeId eigenfieldId, String newName) {
        repository.get().renameEigenfield(eigenfieldId, newName);
        explorer.refresh();
        selectNode(eigenfieldId);
        saveProjectQuietly();
    }

    public void promoteSelectedSnapshotToSequence() {
        promoteSnapshotForNode(inspector.inspectedNodeId.get());
    }

    public void promoteActiveSnapshotToSequence() {
        // Prefer the inspected node (what the user is looking at in the Inspector),
        // falling back to the workspace active node.
        var target = inspector.inspectedNodeId.get();
        if (target == null) target = workspace.activeNodeId.get();
        promoteSnapshotForNode(target);
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

        // Auto-create a simulation config from the source capture's field parameters
        var capture = activeCapture.activeCapture.get();
        var fieldMap = capture != null ? capture.field() : null;
        var params = ax.xz.mri.service.ObjectFactory.extractFromFieldMap(fieldMap);
        var fields = ax.xz.mri.service.ObjectFactory.fieldsFromImport(fieldMap, repo);
        double referenceB0 = ax.xz.mri.service.ObjectFactory.extractB0(fieldMap);
        var config = ax.xz.mri.service.ObjectFactory.buildConfig(params, referenceB0, fields);

        var configDoc = new SimulationConfigDocument(
            new ProjectNodeId("simcfg-" + UUID.randomUUID()),
            baseName + " Config", config);
        repo.addSimConfig(configDoc);

        // Associate the config with the sequence (single source of truth: on the sequence)
        var updatedSeq = new SequenceDocument(
            sequence.id(), sequence.name(), sequence.segments(), sequence.pulse(),
            sequence.clipSequence(), configDoc.id());
        repo.removeSequence(sequence.id());
        repo.addSequence(updatedSeq);

        explorer.refresh();
        selectNode(updatedSeq.id());
        openNode(updatedSeq.id());
        saveProjectQuietly();
    }

    /** Auto-save the project to disk if a project root is set. Silent on failure. */
    public void saveProjectQuietly() {
        var root = projectRoot.get();
        if (root == null) return;
        try { saveProject(root); } catch (IOException ignored) {}
    }

    private void notifyCaptureOpened(ProjectNodeId nodeId) {
        if (onCaptureOpened != null) {
            var capture = activeCapture.activeCapture.get();
            if (capture != null) onCaptureOpened.accept(nodeId, capture);
        }
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

    /**
     * Create a simulation config from a template.
     *
     * @param name     display name
     * @param template the template to use for field creation
     * @param params   physics parameters
     * @return the created document
     */
    public SimulationConfigDocument createSimConfig(
            String name, ax.xz.mri.model.simulation.SimConfigTemplate template,
            ax.xz.mri.service.ObjectFactory.PhysicsParams params) {
        var repo = repository.get();
        var fields = template.createFields(repo);
        var config = ax.xz.mri.service.ObjectFactory.buildConfig(params, template.referenceB0Tesla(), fields);
        var doc = new SimulationConfigDocument(
            new ProjectNodeId("simcfg-" + UUID.randomUUID()), name, config);
        repo.addSimConfig(doc);
        explorer.refresh();
        saveProjectQuietly();
        return doc;
    }

    /** Create a standalone eigenfield document with the given script as the starter. */
    public EigenfieldDocument createEigenfield(String name, String description, String script, String units) {
        var repo = repository.get();
        var ef = ax.xz.mri.service.ObjectFactory.findOrCreateEigenfield(repo, name, description, script, units);
        explorer.refresh();
        saveProjectQuietly();
        return ef;
    }

    /** Create an empty sequence with a single zero-channel segment. */
    public ax.xz.mri.project.SequenceDocument createEmptySequence(String name) {
        var repo = repository.get();
        // Zero channels — the sequence editor will grow steps when a sim-config is associated.
        var steps = new java.util.ArrayList<ax.xz.mri.model.sequence.PulseStep>();
        for (int i = 0; i < 100; i++) steps.add(new ax.xz.mri.model.sequence.PulseStep(new double[0], 0));
        var doc = new ax.xz.mri.project.SequenceDocument(
            new ProjectNodeId("seq-" + UUID.randomUUID()), name,
            List.of(new ax.xz.mri.model.sequence.Segment(1e-5, 0, 100)),
            List.of(new ax.xz.mri.model.sequence.PulseSegment(steps))
        );
        repo.addSequence(doc);
        explorer.refresh();
        saveProjectQuietly();
        return doc;
    }

    private static String slug(String value) {
        String collapsed = value == null ? "untitled" : value.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-");
        collapsed = collapsed.replaceAll("^-+", "").replaceAll("-+$", "");
        return collapsed.isBlank() ? "untitled" : collapsed;
    }
}
