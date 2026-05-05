package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.sequence.ClipBaker;
import ax.xz.mri.ui.wizard.starters.SequenceStarter;
import ax.xz.mri.ui.wizard.starters.SequenceStarterLibrary;
import ax.xz.mri.ui.wizard.starters.SimConfigTemplate;
import ax.xz.mri.hardware.HardwareConfig;
import ax.xz.mri.hardware.HardwarePlugin;
import ax.xz.mri.hardware.HardwarePluginRegistry;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.HardwareConfigDocument;
import ax.xz.mri.project.ProjectManifest;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.state.Autosaver;
import ax.xz.mri.state.Mutation;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.state.ProjectStateIO;
import ax.xz.mri.state.RecordSurgery;
import ax.xz.mri.state.Scope;
import ax.xz.mri.state.UnifiedStateManager;
import ax.xz.mri.model.simulation.PhysicsParams;
import ax.xz.mri.model.simulation.SimulationConfig;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.time.Instant;
import java.util.Objects;

/**
 * Project-first selection state and load/save orchestration.
 *
 * <p>The authoritative project state is held by {@link UnifiedStateManager}.
 * This view-model exposes UI-side selection / inspection state and routes
 * structural mutations (add / rename / delete documents) through
 * {@link UnifiedStateManager#dispatch(Mutation)} so they participate in
 * undo/redo and autosave like every other edit.
 */
public final class ProjectSessionViewModel {

    public final ObjectProperty<Path> projectRoot = new SimpleObjectProperty<>();
    public final ExplorerTreeViewModel explorer = new ExplorerTreeViewModel();
    public final WorkspaceSelectionViewModel workspace = new WorkspaceSelectionViewModel();
    public final InspectorViewModel inspector = new InspectorViewModel();

    private final UnifiedStateManager state;
    private final ProjectStateIO projectIO;

    private Consumer<SimulationConfigDocument> onSimConfigOpened;
    private Consumer<HardwareConfigDocument> onHardwareConfigOpened;
    private Consumer<SequenceDocument> onSequenceOpened;
    private Consumer<EigenfieldDocument> onEigenfieldOpened;
    private BiConsumer<ProjectNodeId, ProjectNodeId> onNodeSelected;
    private Consumer<Throwable> errorSink = ex -> { };

    public ProjectSessionViewModel(UnifiedStateManager state, ProjectStateIO projectIO) {
        this.state = state;
        this.projectIO = projectIO;
        // Refresh the explorer whenever state changes — the explorer reads
        // through state.current() so the structure follows.
        state.currentProperty().addListener((obs, o, n) -> explorer.refresh());
    }

    /** Test helper: build a standalone session with a fresh state manager. */
    public static ProjectSessionViewModel standalone() {
        var surgery = new RecordSurgery();
        var io = new ProjectStateIO();
        var saver = new Autosaver(io::write, null);
        var manager = new UnifiedStateManager(ProjectState.empty(), surgery, saver, null);
        return new ProjectSessionViewModel(manager, io);
    }

    /** The unified state manager backing this project session. */
    public UnifiedStateManager state() { return state; }

    /** Read-only view of the current project state. */
    public ReadOnlyObjectProperty<ProjectState> currentState() { return state.currentProperty(); }

    /** Convenience: the current state value. */
    public ProjectState project() { return state.current(); }

    /** Attach a diagnostics sink (typically MessagesViewModel::logWarning-bridging). */
    public void setErrorSink(Consumer<Throwable> sink) {
        this.errorSink = sink != null ? sink : ex -> { };
    }

    public void setOnSimConfigOpened(Consumer<SimulationConfigDocument> callback) {
        this.onSimConfigOpened = callback;
    }
    public void setOnHardwareConfigOpened(Consumer<HardwareConfigDocument> callback) {
        this.onHardwareConfigOpened = callback;
    }
    public void setOnSequenceOpened(Consumer<SequenceDocument> callback) {
        this.onSequenceOpened = callback;
    }
    public void setOnEigenfieldOpened(Consumer<EigenfieldDocument> callback) {
        this.onEigenfieldOpened = callback;
    }

    /* ── Lifecycle: open / save ────────────────────────────────────────── */

    public void saveProject(Path root) throws IOException {
        var current = state.current();
        if ("Untitled Project".equals(current.manifest().name())) {
            current = current.withManifest(new ProjectManifest(
                root.getFileName().toString(),
                current.manifest().layoutFile(),
                current.manifest().uiStateFile()));
            state.replaceState(current);
        }
        projectRoot.set(root.toAbsolutePath().normalize());
        state.autosaver().setProjectRoot(projectRoot.get());
        projectIO.write(current, root);
        explorer.refresh();
    }

    public void openProject(Path root) throws IOException {
        var loaded = projectIO.read(root);
        state.replaceState(loaded);
        // Restore the persisted undo log so Ctrl+Z survives restart.
        var persistedLog = new ax.xz.mri.state.UndoLogPersistence().read(root);
        if (!persistedLog.isEmpty()) state.installUndoLog(persistedLog);
        projectRoot.set(root.toAbsolutePath().normalize());
        state.autosaver().setProjectRoot(projectRoot.get());
        workspace.activeNodeId.set(null);
        inspector.inspectedNodeId.set(null);
        explorer.refresh();
        if (!loaded.sequenceIds().isEmpty()) {
            openNode(loaded.sequenceIds().getFirst());
        }
    }

    /* ── Selection / opening ───────────────────────────────────────────── */

    public void selectNode(ProjectNodeId nodeId) {
        explorer.selectedNodeId.set(nodeId);
        inspector.inspectedNodeId.set(nodeId);
    }

    public void openNode(ProjectNodeId nodeId) {
        if (nodeId == null) return;
        var node = state.current().node(nodeId);
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
            case HardwareConfigDocument hwConfig -> {
                inspector.inspectedNodeId.set(nodeId);
                if (onHardwareConfigOpened != null) onHardwareConfigOpened.accept(hwConfig);
            }
            case EigenfieldDocument eigen -> {
                inspector.inspectedNodeId.set(nodeId);
                if (onEigenfieldOpened != null) onEigenfieldOpened.accept(eigen);
            }
            case CircuitDocument ignored -> {
                // Circuits don't open standalone — surface their owning sim-config.
                inspector.inspectedNodeId.set(nodeId);
                var owningConfig = state.current().simulationIds().stream()
                    .map(state.current()::simulation)
                    .filter(Objects::nonNull)
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

    /* ── Structural mutations ─────────────────────────────────────────── */

    public void renameSequence(ProjectNodeId sequenceId, String newName) {
        var current = state.current().sequence(sequenceId);
        if (current == null) return;
        var renamed = new SequenceDocument(
            current.id(), newName,
            current.clipSequence(), current.activeSimConfigId(),
            current.preferredHardwareConfigId());
        state.dispatch(new Mutation(seqScope(sequenceId), current, renamed,
            "Rename sequence", Instant.now(), null, Mutation.Category.STRUCTURAL));
        selectNode(sequenceId);
    }

    public void deleteSequence(ProjectNodeId sequenceId) {
        var current = state.current().sequence(sequenceId);
        if (current == null) return;
        state.dispatch(new Mutation(seqScope(sequenceId), current, null,
            "Delete sequence", Instant.now(), null, Mutation.Category.STRUCTURAL));
        if (sequenceId.equals(workspace.activeNodeId.get())) workspace.activeNodeId.set(null);
        if (sequenceId.equals(inspector.inspectedNodeId.get())) inspector.inspectedNodeId.set(null);
    }

    public void deleteSimConfig(ProjectNodeId configId) {
        var current = state.current().simulation(configId);
        if (current == null) return;
        state.dispatch(new Mutation(simScope(configId), current, null,
            "Delete simulation config", Instant.now(), null, Mutation.Category.STRUCTURAL));
        if (configId.equals(inspector.inspectedNodeId.get())) inspector.inspectedNodeId.set(null);
    }

    public void renameSimConfig(ProjectNodeId configId, String newName) {
        var current = state.current().simulation(configId);
        if (current == null) return;
        var renamed = new SimulationConfigDocument(current.id(), newName, current.config());
        state.dispatch(new Mutation(simScope(configId), current, renamed,
            "Rename simulation config", Instant.now(), null, Mutation.Category.STRUCTURAL));
        selectNode(configId);
    }

    public HardwareConfigDocument createHardwareConfig(String name, String pluginId) {
        var plugin = HardwarePluginRegistry.byId(pluginId).orElseThrow(() ->
            new IllegalArgumentException("No hardware plugin registered for id: " + pluginId));
        return createHardwareConfig(name, plugin, plugin.defaultConfig());
    }

    public HardwareConfigDocument createHardwareConfig(String name, HardwarePlugin plugin, HardwareConfig config) {
        var doc = HardwareConfigDocument.of(new ProjectNodeId("hwcfg-" + UUID.randomUUID()), name, config);
        state.dispatch(new Mutation(hwScope(doc.id()), null, doc,
            "Create hardware config", Instant.now(), null, Mutation.Category.STRUCTURAL));
        return doc;
    }

    public void updateHardwareConfig(HardwareConfigDocument updated) {
        var current = state.current().hardwareConfig(updated.id());
        state.dispatch(new Mutation(hwScope(updated.id()), current, updated,
            "Update hardware config", Instant.now(), null, Mutation.Category.CONTENT));
    }

    public void deleteHardwareConfig(ProjectNodeId configId) {
        var current = state.current().hardwareConfig(configId);
        if (current == null) return;
        state.dispatch(new Mutation(hwScope(configId), current, null,
            "Delete hardware config", Instant.now(), null, Mutation.Category.STRUCTURAL));
        if (configId.equals(inspector.inspectedNodeId.get())) inspector.inspectedNodeId.set(null);
    }

    public void renameHardwareConfig(ProjectNodeId configId, String newName) {
        var current = state.current().hardwareConfig(configId);
        if (current == null) return;
        var renamed = current.withName(newName);
        state.dispatch(new Mutation(hwScope(configId), current, renamed,
            "Rename hardware config", Instant.now(), null, Mutation.Category.STRUCTURAL));
        selectNode(configId);
    }

    public SimulationConfigDocument duplicateSimConfig(ProjectNodeId sourceId) {
        var st = state.current();
        var source = st.simulation(sourceId);
        if (source == null) return null;

        var existingNames = new java.util.HashSet<String>();
        for (var id : st.simulationIds()) {
            var d = st.simulation(id);
            if (d != null) existingNames.add(d.name());
        }
        var newName = uniqueName(existingNames, source.name() + " (copy)");

        var sourceConfig = source.config();
        ProjectNodeId newCircuitId = sourceConfig.circuitId();
        CircuitDocument clonedCircuit = null;
        if (sourceConfig.circuitId() != null) {
            var sourceCircuit = st.circuit(sourceConfig.circuitId());
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
                clonedCircuit = new CircuitDocument(newCircuitId, sourceCircuit.name() + " (copy)",
                    clonedComponents, clonedWires, clonedLayout);
            }
        }
        var clonedConfig = sourceConfig.withCircuitId(newCircuitId);
        var newDoc = new SimulationConfigDocument(
            new ProjectNodeId("simcfg-" + UUID.randomUUID()), newName, clonedConfig);

        // Two structural mutations — circuit first (so the FK resolves), then sim config.
        if (clonedCircuit != null) {
            state.dispatch(new Mutation(circuitScope(clonedCircuit.id()), null, clonedCircuit,
                "Duplicate circuit", Instant.now(), null, Mutation.Category.STRUCTURAL));
        }
        state.dispatch(new Mutation(simScope(newDoc.id()), null, newDoc,
            "Duplicate simulation config", Instant.now(), null, Mutation.Category.STRUCTURAL));
        selectNode(newDoc.id());
        return newDoc;
    }

    public void deleteEigenfield(ProjectNodeId eigenfieldId) {
        var current = state.current().eigenfield(eigenfieldId);
        if (current == null) return;
        state.dispatch(new Mutation(efScope(eigenfieldId), current, null,
            "Delete eigenfield", Instant.now(), null, Mutation.Category.STRUCTURAL));
        if (eigenfieldId.equals(inspector.inspectedNodeId.get())) inspector.inspectedNodeId.set(null);
    }

    public void renameEigenfield(ProjectNodeId eigenfieldId, String newName) {
        var current = state.current().eigenfield(eigenfieldId);
        if (current == null) return;
        var renamed = current.withName(newName);
        state.dispatch(new Mutation(efScope(eigenfieldId), current, renamed,
            "Rename eigenfield", Instant.now(), null, Mutation.Category.STRUCTURAL));
        selectNode(eigenfieldId);
    }

    public SimulationConfigDocument createSimConfig(String name, SimConfigTemplate template, PhysicsParams params) {
        var built = template.buildCircuit(state.current(), name + " circuit");
        // Dispatch any eigenfields the starter required first, then the circuit (so FKs resolve).
        for (var ef : built.newEigenfields()) {
            state.dispatch(new Mutation(efScope(ef.id()), null, ef,
                "Create eigenfield", Instant.now(), null, Mutation.Category.STRUCTURAL));
        }
        var circuit = built.circuit();
        if (circuit != null) {
            state.dispatch(new Mutation(circuitScope(circuit.id()), null, circuit,
                "Create circuit", Instant.now(), null, Mutation.Category.STRUCTURAL));
        }
        var config = SimulationConfig.fromPhysics(params, template.referenceB0Tesla(),
            circuit != null ? circuit.id() : null);
        var doc = new SimulationConfigDocument(
            new ProjectNodeId("simcfg-" + UUID.randomUUID()), name, config);
        state.dispatch(new Mutation(simScope(doc.id()), null, doc,
            "Create simulation config", Instant.now(), null, Mutation.Category.STRUCTURAL));
        return doc;
    }

    public EigenfieldDocument createEigenfield(String name, String description, String script, String units) {
        // Dedup by (name, script) — match the legacy findOrCreate semantics.
        var st = state.current();
        for (var id : st.eigenfieldIds()) {
            var ef = st.eigenfield(id);
            if (ef != null && ef.name().equals(name) && ef.script().equals(script)) return ef;
        }
        var fresh = new EigenfieldDocument(
            new ProjectNodeId("ef-" + UUID.randomUUID()), name, description, script, units);
        state.dispatch(new Mutation(efScope(fresh.id()), null, fresh,
            "Create eigenfield", Instant.now(), null, Mutation.Category.STRUCTURAL));
        return fresh;
    }

    public SequenceDocument createEmptySequence(String name, ProjectNodeId configId) {
        return createSequenceFromStarter(name, configId, SequenceStarterLibrary.defaultStarter());
    }

    public SequenceDocument createSequenceFromStarter(String name, ProjectNodeId configId, SequenceStarter starter) {
        if (starter == null) starter = SequenceStarterLibrary.defaultStarter();
        var st = state.current();
        var configDoc = st.simulation(configId);
        var config = configDoc != null ? configDoc.config() : null;
        CircuitDocument circuit = config != null ? st.circuit(config.circuitId()) : null;

        var clipSeq = starter.build(config, circuit);
        var doc = new SequenceDocument(
            new ProjectNodeId("seq-" + UUID.randomUUID()), name,
            clipSeq, configId, null
        );
        state.dispatch(new Mutation(seqScope(doc.id()), null, doc,
            "Create sequence", Instant.now(), null, Mutation.Category.STRUCTURAL));
        return doc;
    }

    /** Force a flush of any pending autosave (e.g. on app close). */
    public void flushSave() {
        state.autosaver().flush();
    }

    /* ── helpers ──────────────────────────────────────────────────────── */

    private static Scope seqScope(ProjectNodeId id)     { return Scope.indexed(Scope.root(), "sequences", id); }
    private static Scope simScope(ProjectNodeId id)     { return Scope.indexed(Scope.root(), "simulations", id); }
    private static Scope efScope(ProjectNodeId id)      { return Scope.indexed(Scope.root(), "eigenfields", id); }
    private static Scope circuitScope(ProjectNodeId id) { return Scope.indexed(Scope.root(), "circuits", id); }
    private static Scope hwScope(ProjectNodeId id)      { return Scope.indexed(Scope.root(), "hardware", id); }

    private static String uniqueName(java.util.Set<String> existing, String base) {
        if (!existing.contains(base)) return base;
        int i = 2;
        while (existing.contains(base + " " + i)) i++;
        return base + " " + i;
    }
}
