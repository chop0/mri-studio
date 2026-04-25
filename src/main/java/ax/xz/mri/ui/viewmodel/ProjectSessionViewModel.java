package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.circuit.CircuitDocument;
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

        var currentCircuitSlugs = new HashSet<String>();
        for (var circuitId : repo.circuitIds()) {
            var circuit = (CircuitDocument) repo.node(circuitId);
            String cSlug = slug(circuit.name());
            currentCircuitSlugs.add(cSlug);
            serialiser.writeJson(root.resolve("circuits").resolve(cSlug).resolve("circuit.json"), circuit);
        }
        cleanupDeletedDirs(root.resolve("circuits"), currentCircuitSlugs);

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

        var eigenfieldsDir = root.resolve("eigenfields");
        if (Files.isDirectory(eigenfieldsDir)) {
            try (var files = Files.walk(eigenfieldsDir)) {
                for (var path : files.filter(candidate -> candidate.getFileName().toString().equals("eigenfield.json")).toList()) {
                    loadedRepository.addEigenfield(serialiser.readJson(path, EigenfieldDocument.class));
                }
            }
        }

        // Circuits must load before sim-configs so that config.circuitId resolves to a present node.
        var circuitsDir = root.resolve("circuits");
        if (Files.isDirectory(circuitsDir)) {
            try (var files = Files.walk(circuitsDir)) {
                for (var path : files.filter(candidate -> candidate.getFileName().toString().equals("circuit.json")).toList()) {
                    loadedRepository.addCircuit(serialiser.readJson(path, CircuitDocument.class));
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
            case CircuitDocument ignored -> {
                // Circuits don't open standalone — surface their owning sim-config's editor
                // and let the user navigate to the Schematic tab.
                inspector.inspectedNodeId.set(nodeId);
                var owningConfig = repo.simConfigIds().stream()
                    .map(id -> (SimulationConfigDocument) repo.node(id))
                    .filter(java.util.Objects::nonNull)
                    .filter(cfg -> cfg.config() != null && nodeId.equals(cfg.config().circuitId()))
                    .findFirst().orElse(null);
                if (owningConfig != null && onSimConfigOpened != null) {
                    onSimConfigOpened.accept(owningConfig);
                }
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

    /**
     * Deep-copy a simulation config: its {@link CircuitDocument} is cloned
     * with fresh component ids (so both configs can be edited independently
     * without mutating a shared circuit), a new config doc wraps it, and
     * the duplicate is added to the repo with a disambiguated name. Returns
     * the new doc so the caller can open it.
     */
    public SimulationConfigDocument duplicateSimConfig(ProjectNodeId sourceId) {
        var repo = repository.get();
        if (!(repo.node(sourceId) instanceof SimulationConfigDocument source)) return null;

        var existingNames = new java.util.HashSet<String>();
        for (var id : repo.simConfigIds()) {
            if (repo.node(id) instanceof SimulationConfigDocument d) existingNames.add(d.name());
        }
        var newName = uniqueName(existingNames, source.name() + " (copy)");

        var sourceConfig = source.config();
        ProjectNodeId newCircuitId = sourceConfig.circuitId();
        if (sourceConfig.circuitId() != null) {
            var sourceCircuit = repo.circuit(sourceConfig.circuitId());
            if (sourceCircuit != null) {
                newCircuitId = new ProjectNodeId("circuit-" + UUID.randomUUID());
                var clonedComponents = new java.util.ArrayList<ax.xz.mri.model.circuit.CircuitComponent>();
                var componentIdMap = new java.util.HashMap<ax.xz.mri.model.circuit.ComponentId, ax.xz.mri.model.circuit.ComponentId>();
                for (var comp : sourceCircuit.components()) {
                    var newId = new ax.xz.mri.model.circuit.ComponentId(comp.id().value() + "-dup-" + UUID.randomUUID());
                    componentIdMap.put(comp.id(), newId);
                    clonedComponents.add(comp.withId(newId));
                }
                var clonedWires = new java.util.ArrayList<ax.xz.mri.model.circuit.Wire>();
                for (var wire : sourceCircuit.wires()) {
                    var fromId = componentIdMap.getOrDefault(wire.from().componentId(), wire.from().componentId());
                    var toId = componentIdMap.getOrDefault(wire.to().componentId(), wire.to().componentId());
                    clonedWires.add(new ax.xz.mri.model.circuit.Wire(
                        "wire-" + UUID.randomUUID(),
                        new ax.xz.mri.model.circuit.ComponentTerminal(fromId, wire.from().port()),
                        new ax.xz.mri.model.circuit.ComponentTerminal(toId, wire.to().port())));
                }
                var clonedLayout = ax.xz.mri.model.circuit.CircuitLayout.empty();
                for (var entry : sourceCircuit.layout().positions().entrySet()) {
                    var newId = componentIdMap.get(entry.getKey());
                    if (newId == null) continue;
                    var pos = entry.getValue();
                    clonedLayout = clonedLayout.with(new ax.xz.mri.model.circuit.ComponentPosition(
                        newId, pos.x(), pos.y(), pos.rotationQuarters(), pos.mirrored()));
                }
                var clonedCircuit = new ax.xz.mri.model.circuit.CircuitDocument(
                    newCircuitId, sourceCircuit.name() + " (copy)",
                    clonedComponents, clonedWires, clonedLayout);
                repo.addCircuit(clonedCircuit);
            }
        }
        var clonedConfig = sourceConfig.withCircuitId(newCircuitId);
        var newDoc = new SimulationConfigDocument(
            new ProjectNodeId("simcfg-" + UUID.randomUUID()), newName, clonedConfig);
        repo.addSimConfig(newDoc);
        explorer.refresh();
        selectNode(newDoc.id());
        saveProjectQuietly();
        return newDoc;
    }

    private static String uniqueName(java.util.Set<String> existing, String base) {
        if (!existing.contains(base)) return base;
        int i = 2;
        while (existing.contains(base + " " + i)) i++;
        return base + " " + i;
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
        var circuitId = template.createCircuit(repo, name + " circuit");
        var config = ObjectFactory.buildConfig(params, template.referenceB0Tesla(), circuitId);
        var doc = new SimulationConfigDocument(
            new ProjectNodeId("simcfg-" + UUID.randomUUID()), name, config);
        repo.addSimConfig(doc);
        explorer.refresh();
        saveProjectQuietly();
        return doc;
    }

    public EigenfieldDocument createEigenfield(String name, String description, String script, String units) {
        var repo = repository.get();
        var ef = ObjectFactory.findOrCreateEigenfield(repo, name, description, script, units);
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
        CircuitDocument circuit = config != null ? repo.circuit(config.circuitId()) : null;

        var clipSeq = starter.build(config, circuit);
        var baked = ClipBaker.bake(clipSeq, circuit);

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
