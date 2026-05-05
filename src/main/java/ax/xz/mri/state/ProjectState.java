package ax.xz.mri.state;

import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.HardwareConfigDocument;
import ax.xz.mri.project.ProjectManifest;
import ax.xz.mri.project.ProjectNode;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.project.SimulationConfigDocument;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single source of truth for a project's persistent state.
 *
 * <p>An immutable record holding every persisted document by id. The five
 * document maps are insertion-ordered ({@link java.util.LinkedHashMap}-backed
 * unmodifiable wrappers) — order is part of the user-visible UX, but rather
 * than carry a second {@code List<Id>} order field per type (which lets
 * direct map mutations drift the two), iteration order IS the order. Adding
 * a doc via a {@link Mutation} on the map automatically appends; removing
 * deletes the slot.
 *
 * <p>Every mutation runs through {@link UnifiedStateManager#dispatch}, which
 * applies the change atomically and schedules autosave. There is no other
 * legal write path.
 */
public record ProjectState(
    ProjectManifest manifest,
    Map<ProjectNodeId, SequenceDocument> sequences,
    Map<ProjectNodeId, SimulationConfigDocument> simulations,
    Map<ProjectNodeId, EigenfieldDocument> eigenfields,
    Map<ProjectNodeId, CircuitDocument> circuits,
    Map<ProjectNodeId, HardwareConfigDocument> hardware
) {

    public ProjectState {
        if (manifest == null) throw new IllegalArgumentException("manifest must not be null");
        sequences = freezeMap(sequences);
        simulations = freezeMap(simulations);
        eigenfields = freezeMap(eigenfields);
        circuits = freezeMap(circuits);
        hardware = freezeMap(hardware);
    }

    public static ProjectState empty() {
        return new ProjectState(
            new ProjectManifest("Untitled Project", ".mri-studio/layout.json", ".mri-studio/ui-state.json"),
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of()
        );
    }

    public static ProjectState fresh(ProjectManifest manifest) {
        return new ProjectState(manifest, Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    /* Insertion-ordered id lists, derived live from each map's key set. */

    public List<ProjectNodeId> sequenceIds()    { return List.copyOf(sequences.keySet()); }
    public List<ProjectNodeId> simulationIds()  { return List.copyOf(simulations.keySet()); }
    public List<ProjectNodeId> eigenfieldIds()  { return List.copyOf(eigenfields.keySet()); }
    public List<ProjectNodeId> circuitIds()     { return List.copyOf(circuits.keySet()); }
    public List<ProjectNodeId> hardwareIds()    { return List.copyOf(hardware.keySet()); }

    /** Lookup any node by id, walking all five maps. Returns {@code null} if no match. */
    public ProjectNode node(ProjectNodeId id) {
        if (id == null) return null;
        ProjectNode n;
        if ((n = sequences.get(id)) != null) return n;
        if ((n = simulations.get(id)) != null) return n;
        if ((n = eigenfields.get(id)) != null) return n;
        if ((n = circuits.get(id)) != null) return n;
        if ((n = hardware.get(id)) != null) return n;
        return null;
    }

    public boolean contains(ProjectNodeId id) {
        return node(id) != null;
    }

    public SequenceDocument sequence(ProjectNodeId id)               { return sequences.get(id); }
    public SimulationConfigDocument simulation(ProjectNodeId id)     { return simulations.get(id); }
    public EigenfieldDocument eigenfield(ProjectNodeId id)           { return eigenfields.get(id); }
    public CircuitDocument circuit(ProjectNodeId id)                 { return circuits.get(id); }
    public HardwareConfigDocument hardwareConfig(ProjectNodeId id)   { return hardware.get(id); }

    /** Builder-style helpers for adding/replacing/removing documents. Each returns a new state. */

    public ProjectState withSequence(SequenceDocument doc) {
        return new ProjectState(manifest,
            putInto(sequences, doc.id(), doc),
            simulations, eigenfields, circuits, hardware);
    }
    public ProjectState withoutSequence(ProjectNodeId id) {
        return new ProjectState(manifest,
            removeFrom(sequences, id),
            simulations, eigenfields, circuits, hardware);
    }

    public ProjectState withSimulation(SimulationConfigDocument doc) {
        return new ProjectState(manifest,
            sequences,
            putInto(simulations, doc.id(), doc),
            eigenfields, circuits, hardware);
    }
    public ProjectState withoutSimulation(ProjectNodeId id) {
        return new ProjectState(manifest,
            sequences,
            removeFrom(simulations, id),
            eigenfields, circuits, hardware);
    }

    public ProjectState withEigenfield(EigenfieldDocument doc) {
        return new ProjectState(manifest,
            sequences, simulations,
            putInto(eigenfields, doc.id(), doc),
            circuits, hardware);
    }
    public ProjectState withoutEigenfield(ProjectNodeId id) {
        return new ProjectState(manifest,
            sequences, simulations,
            removeFrom(eigenfields, id),
            circuits, hardware);
    }

    public ProjectState withCircuit(CircuitDocument doc) {
        return new ProjectState(manifest,
            sequences, simulations, eigenfields,
            putInto(circuits, doc.id(), doc),
            hardware);
    }
    public ProjectState withoutCircuit(ProjectNodeId id) {
        return new ProjectState(manifest,
            sequences, simulations, eigenfields,
            removeFrom(circuits, id),
            hardware);
    }

    public ProjectState withHardware(HardwareConfigDocument doc) {
        return new ProjectState(manifest,
            sequences, simulations, eigenfields, circuits,
            putInto(hardware, doc.id(), doc));
    }
    public ProjectState withoutHardware(ProjectNodeId id) {
        return new ProjectState(manifest,
            sequences, simulations, eigenfields, circuits,
            removeFrom(hardware, id));
    }

    public ProjectState withManifest(ProjectManifest m) {
        return new ProjectState(m, sequences, simulations, eigenfields, circuits, hardware);
    }

    /* ── helpers ──────────────────────────────────────────────────────────── */

    private static <K, V> Map<K, V> putInto(Map<K, V> m, K key, V value) {
        var copy = new LinkedHashMap<>(m);
        copy.put(key, value);
        return java.util.Collections.unmodifiableMap(copy);
    }

    private static <K, V> Map<K, V> removeFrom(Map<K, V> m, K key) {
        if (!m.containsKey(key)) return m;
        var copy = new LinkedHashMap<>(m);
        copy.remove(key);
        return java.util.Collections.unmodifiableMap(copy);
    }

    /**
     * Freeze a (possibly mutable) input map into an unmodifiable wrapper that
     * preserves insertion order. {@link Map#copyOf} doesn't guarantee
     * iteration order, which would corrupt the user-visible doc order.
     */
    private static <K, V> Map<K, V> freezeMap(Map<K, V> m) {
        if (m == null || m.isEmpty()) return java.util.Collections.unmodifiableMap(new LinkedHashMap<>());
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }
}
