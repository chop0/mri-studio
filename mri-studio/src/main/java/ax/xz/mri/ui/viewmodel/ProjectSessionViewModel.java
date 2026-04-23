package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.sequence.ClipBaker;
import ax.xz.mri.model.sequence.SequenceStarter;
import ax.xz.mri.model.sequence.SequenceStarterLibrary;
import ax.xz.mri.model.simulation.SimConfigTemplate;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectManifest;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.project.ProjectSerialiser;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.service.ObjectFactory;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Project-first selection state and load/save orchestration. */
public final class ProjectSessionViewModel {
    public final ObjectProperty<ProjectRepository> repository = new SimpleObjectProperty<>(ProjectRepository.untitled());
    public final ObjectProperty<Path> projectRoot = new SimpleObjectProperty<>();
    public final ExplorerTreeViewModel explorer = new ExplorerTreeViewModel();
    public final WorkspaceSelectionViewModel workspace = new WorkspaceSelectionViewModel();
    public final InspectorViewModel inspector = new InspectorViewModel();

    private final ProjectSerialiser serialiser = new ProjectSerialiser();

    private Consumer<SimulationConfigDocument> onSimConfigOpened;
    private Consumer<SequenceDocument> onSequenceOpened;
    private Consumer<EigenfieldDocument> onEigenfieldOpened;
    private BiConsumer<ProjectNodeId, ProjectNodeId> onNodeSelected;

    public void setOnSimConfigOpened(Consumer<SimulationConfigDocument> callback) {
        this.onSimConfigOpened = callback;
    }
    public void setOnSequenceOpened(Consumer<SequenceDocument> callback) {
        this.onSequenceOpened = callback;
    }
    public void setOnEigenfieldOpened(Consumer<EigenfieldDocument> callback) {
        this.onEigenfieldOpened = callback;
    }

    public ProjectSessionViewModel() {
        repository.addListener((obs, oldValue, newValue) -> explorer.refresh());
    }

    public void saveProject(Path root) throws IOException {
        Files.createDirectories(root);
        projectRoot.set(root.toAbsolutePath().normalize());

        var repo = repository.get();
        if ("Untitled Project".equals(repo.manifest().name())) {
            repo.setManifest(new ProjectManifest(root.getFileName().toString(), repo.manifest().layoutFile(), repo.manifest().uiStateFile()));
        }

        serialiser.writeManifest(root.resolve("mri-project.toml"), repo.manifest());

        var currentSequenceSlugs = new HashSet<String>();
        for (var sequenceId : repo.sequenceIds()) {
            var sequence = (SequenceDocument) repo.node(sequenceId);
            String seqSlug = slug(sequence.name());
            currentSequenceSlugs.add(seqSlug);
            serialiser.writeJson(root.resolve("sequences").resolve(seqSlug).resolve("sequence.json"), sequence);
        }
        cleanupDeletedDirs(root.resolve("sequences"), currentSequenceSlugs);

        var currentSimSlugs = new HashSet<String>();
        for (var configId : repo.simConfigIds()) {
            var config = (SimulationConfigDocument) repo.node(configId);
            String simSlug = slug(config.name());
            currentSimSlugs.add(simSlug);
            serialiser.writeJson(root.resolve("simulations").resolve(simSlug).resolve("config.json"), config);
        }
        cleanupDeletedDirs(root.resolve("simulations"), currentSimSlugs);

        var currentEigenfieldSlugs = new HashSet<String>();
        for (var eigenfieldId : repo.eigenfieldIds()) {
            var eigenfield = (EigenfieldDocument) repo.node(eigenfieldId);
            String efSlug = slug(eigenfield.name());
            currentEigenfieldSlugs.add(efSlug);
            serialiser.writeJson(root.resolve("eigenfields").resolve(efSlug).resolve("eigenfield.json"), eigenfield);
        }
        cleanupDeletedDirs(root.resolve("eigenfields"), currentEigenfieldSlugs);

        explorer.refresh();
    }

    private static void cleanupDeletedDirs(Path parentDir, java.util.Set<String> activeSlugs) throws IOException {
        if (!Files.isDirectory(parentDir)) return;
        try (var dirs = Files.list(parentDir)) {
            for (var dir : dirs.filter(Files::isDirectory).toList()) {
                if (!activeSlugs.contains(dir.getFileName().toString())) {
                    try (var walk = Files.walk(dir)) {
                        walk.sorted(Comparator.reverseOrder()).forEach(p -> {
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
        explorer.refresh();
        if (!loadedRepository.sequenceIds().isEmpty()) {
            openNode(loadedRepository.sequenceIds().getFirst());
        }
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
            case SequenceDocument seq -> {
                workspace.activeNodeId.set(nodeId);
                inspector.inspectedNodeId.set(nodeId);
                if (onSequenceOpened != null) onSequenceOpened.accept(seq);
            }
            case SimulationConfigDocument simConfig -> {
                inspector.inspectedNodeId.set(nodeId);
                if (onSimConfigOpened != null) onSimConfigOpened.accept(simConfig);
            }
            case EigenfieldDocument eigen -> {
                inspector.inspectedNodeId.set(nodeId);
                if (onEigenfieldOpened != null) onEigenfieldOpened.accept(eigen);
            }
            default -> {
                workspace.activeNodeId.set(nodeId);
                inspector.inspectedNodeId.set(nodeId);
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
        if (sequenceId.equals(workspace.activeNodeId.get())) {
            workspace.activeNodeId.set(null);
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

    public void saveProjectQuietly() {
        var root = projectRoot.get();
        if (root == null) return;
        try { saveProject(root); } catch (IOException ignored) {}
    }

    public SimulationConfigDocument createSimConfig(String name, SimConfigTemplate template, ObjectFactory.PhysicsParams params) {
        var repo = repository.get();
        var transmitCoils = template.createTransmitCoils(repo);
        var drivePaths = template.createDrivePaths(repo);
        var receiveCoils = template.createReceiveCoils(repo);
        var config = ObjectFactory.buildConfig(params, template.referenceB0Tesla(),
            transmitCoils, drivePaths, receiveCoils);
        var doc = new SimulationConfigDocument(
            new ProjectNodeId("simcfg-" + UUID.randomUUID()), name, config);
        repo.addSimConfig(doc);
        explorer.refresh();
        saveProjectQuietly();
        return doc;
    }

    public EigenfieldDocument createEigenfield(String name, String description, String script, String units, double defaultMagnitude) {
        var repo = repository.get();
        var ef = ObjectFactory.findOrCreateEigenfield(repo, name, description, script, units, defaultMagnitude);
        explorer.refresh();
        saveProjectQuietly();
        return ef;
    }

    public SequenceDocument createEmptySequence(String name, ProjectNodeId configId) {
        return createSequenceFromStarter(name, configId, SequenceStarterLibrary.defaultStarter());
    }

    public SequenceDocument createSequenceFromStarter(String name, ProjectNodeId configId, SequenceStarter starter) {
        if (starter == null) starter = SequenceStarterLibrary.defaultStarter();
        var repo = repository.get();
        var configDoc = (SimulationConfigDocument) repo.node(configId);
        var config = configDoc != null ? configDoc.config() : null;

        var clipSeq = starter.build(config);
        var baked = ClipBaker.bake(clipSeq, config);

        var doc = new SequenceDocument(
            new ProjectNodeId("seq-" + UUID.randomUUID()), name,
            baked.segments(),
            baked.pulseSegments(),
            clipSeq,
            configId
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
