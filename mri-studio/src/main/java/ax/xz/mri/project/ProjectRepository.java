package ax.xz.mri.project;

import ax.xz.mri.model.circuit.CircuitDocument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** In-memory semantic project repository over project-owned nodes. */
public final class ProjectRepository {
    private ProjectManifest manifest;
    private final Map<ProjectNodeId, ProjectNode> nodes = new LinkedHashMap<>();
    private final List<ProjectNodeId> sequenceIds = new ArrayList<>();
    private final List<ProjectNodeId> simConfigIds = new ArrayList<>();
    private final List<ProjectNodeId> eigenfieldIds = new ArrayList<>();
    private final List<ProjectNodeId> circuitIds = new ArrayList<>();

    public ProjectRepository(ProjectManifest manifest) {
        this.manifest = Objects.requireNonNull(manifest);
    }

    public static ProjectRepository untitled() {
        return new ProjectRepository(new ProjectManifest("Untitled Project", ".mri-studio/layout.json", ".mri-studio/ui-state.json"));
    }

    public ProjectManifest manifest() {
        return manifest;
    }

    public void setManifest(ProjectManifest manifest) {
        this.manifest = Objects.requireNonNull(manifest);
    }

    public List<ProjectNodeId> sequenceIds() {
        return List.copyOf(sequenceIds);
    }

    public List<ProjectNodeId> simConfigIds() {
        return List.copyOf(simConfigIds);
    }

    public List<ProjectNodeId> eigenfieldIds() {
        return List.copyOf(eigenfieldIds);
    }

    public List<ProjectNodeId> circuitIds() {
        return List.copyOf(circuitIds);
    }

    public ProjectNode node(ProjectNodeId id) {
        return nodes.get(id);
    }

    public Optional<ProjectNode> maybeNode(ProjectNodeId id) {
        return Optional.ofNullable(nodes.get(id));
    }

    // --- Sequences ---

    public void addSequence(SequenceDocument sequence) {
        nodes.put(sequence.id(), sequence);
        if (!sequenceIds.contains(sequence.id())) {
            sequenceIds.add(sequence.id());
        }
    }

    public void removeSequence(ProjectNodeId sequenceId) {
        var node = nodes.get(sequenceId);
        if (!(node instanceof SequenceDocument)) {
            throw new IllegalArgumentException("Node " + sequenceId + " is not a sequence");
        }
        nodes.remove(sequenceId);
        sequenceIds.remove(sequenceId);
    }

    public SequenceDocument renameSequence(ProjectNodeId sequenceId, String newName) {
        var node = nodes.get(sequenceId);
        if (!(node instanceof SequenceDocument sequence)) {
            throw new IllegalArgumentException("Node " + sequenceId + " is not a sequence");
        }
        var renamed = new SequenceDocument(
            sequence.id(), newName, sequence.segments(), sequence.pulse(),
            sequence.clipSequence(), sequence.activeSimConfigId());
        nodes.put(sequenceId, renamed);
        return renamed;
    }

    // --- Simulation configs ---

    public void addSimConfig(SimulationConfigDocument config) {
        nodes.put(config.id(), config);
        if (!simConfigIds.contains(config.id())) {
            simConfigIds.add(config.id());
        }
    }

    public void removeSimConfig(ProjectNodeId configId) {
        var node = nodes.get(configId);
        if (!(node instanceof SimulationConfigDocument)) return;
        nodes.remove(configId);
        simConfigIds.remove(configId);
    }

    public SimulationConfigDocument simConfig(ProjectNodeId configId) {
        var node = nodes.get(configId);
        return node instanceof SimulationConfigDocument sc ? sc : null;
    }

    public SimulationConfigDocument renameSimConfig(ProjectNodeId configId, String newName) {
        var node = nodes.get(configId);
        if (!(node instanceof SimulationConfigDocument sc)) {
            throw new IllegalArgumentException("Node " + configId + " is not a simulation config");
        }
        var renamed = sc.withName(newName);
        nodes.put(configId, renamed);
        return renamed;
    }

    // --- Eigenfields ---

    public void addEigenfield(EigenfieldDocument eigenfield) {
        nodes.put(eigenfield.id(), eigenfield);
        if (!eigenfieldIds.contains(eigenfield.id())) {
            eigenfieldIds.add(eigenfield.id());
        }
    }

    public void removeEigenfield(ProjectNodeId eigenfieldId) {
        var node = nodes.get(eigenfieldId);
        if (!(node instanceof EigenfieldDocument)) return;
        nodes.remove(eigenfieldId);
        eigenfieldIds.remove(eigenfieldId);
    }

    public void updateEigenfield(EigenfieldDocument updated) {
        if (!eigenfieldIds.contains(updated.id())) {
            throw new IllegalArgumentException("Eigenfield " + updated.id() + " does not exist");
        }
        nodes.put(updated.id(), updated);
    }

    public EigenfieldDocument renameEigenfield(ProjectNodeId eigenfieldId, String newName) {
        var node = nodes.get(eigenfieldId);
        if (!(node instanceof EigenfieldDocument ef)) {
            throw new IllegalArgumentException("Node " + eigenfieldId + " is not an eigenfield");
        }
        var renamed = ef.withName(newName);
        nodes.put(eigenfieldId, renamed);
        return renamed;
    }

    // --- Circuits ---

    public void addCircuit(CircuitDocument circuit) {
        nodes.put(circuit.id(), circuit);
        if (!circuitIds.contains(circuit.id())) {
            circuitIds.add(circuit.id());
        }
    }

    public void removeCircuit(ProjectNodeId circuitId) {
        var node = nodes.get(circuitId);
        if (!(node instanceof CircuitDocument)) return;
        nodes.remove(circuitId);
        circuitIds.remove(circuitId);
    }

    public CircuitDocument circuit(ProjectNodeId circuitId) {
        var node = nodes.get(circuitId);
        return node instanceof CircuitDocument c ? c : null;
    }

    public void updateCircuit(CircuitDocument updated) {
        if (!circuitIds.contains(updated.id())) {
            throw new IllegalArgumentException("Circuit " + updated.id() + " does not exist");
        }
        nodes.put(updated.id(), updated);
    }

    public CircuitDocument renameCircuit(ProjectNodeId circuitId, String newName) {
        var node = nodes.get(circuitId);
        if (!(node instanceof CircuitDocument circuit)) {
            throw new IllegalArgumentException("Node " + circuitId + " is not a circuit");
        }
        var renamed = circuit.withName(newName);
        nodes.put(circuitId, renamed);
        return renamed;
    }

    public boolean contains(ProjectNodeId id) {
        return nodes.containsKey(id);
    }
}
