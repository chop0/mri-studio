package ax.xz.mri.state;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.model.simulation.PhysicsParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedStateManagerTest {

    @BeforeAll
    static void bypassFxCheck() {
        System.setProperty("ax.xz.mri.state.bypass-fx-check", "true");
    }

    private static UnifiedStateManager newManager(ProjectState initial) {
        var surgery = new RecordSurgery();
        var io = new ProjectStateIO();
        var saver = new Autosaver((s, p) -> io.write(s, p), null);
        return new UnifiedStateManager(initial, surgery, saver, null);
    }

    private static ProjectNodeId circuitId(String s) { return new ProjectNodeId("circuit-" + s); }
    private static ProjectNodeId simId(String s)     { return new ProjectNodeId("simcfg-" + s); }
    private static ProjectNodeId seqId(String s)     { return new ProjectNodeId("seq-" + s); }
    private static ProjectNodeId efId(String s)      { return new ProjectNodeId("ef-" + s); }

    private static CircuitDocument simpleCircuit(String slug) {
        return new CircuitDocument(
            circuitId(slug), "Circuit-" + slug,
            List.of(new CircuitComponent.Resistor(new ComponentId("R1"), "R1", 100.0)),
            List.of(),
            CircuitLayout.empty()
        );
    }

    @Test
    void dispatchUpdatesCurrentAndAppendsToUndoLog() {
        var initial = ProjectState.empty().withCircuit(simpleCircuit("a"));
        var mgr = newManager(initial);
        var resScope = Scope.field(
            Scope.indexed(
                Scope.indexed(Scope.field(Scope.root(), "circuits"), "circuits", circuitId("a")),
                "components", new ComponentId("R1")),
            "resistanceOhms");
        // Wait — the indexed scope's parent must resolve to a record. Let me build it cleanly.
        // doc.circuits is a Map; "circuits" IndexedItem belongs at root with parent=root.
        var properRes = Scope.field(
            Scope.indexed(
                Scope.indexed(Scope.root(), "circuits", circuitId("a")),
                "components", new ComponentId("R1")),
            "resistanceOhms");

        mgr.dispatch(Mutation.content(properRes, 100.0, 220.0, "Edit R"));
        var newR = (CircuitComponent.Resistor) mgr.current().circuit(circuitId("a"))
            .components().get(0);
        assertEquals(220.0, newR.resistanceOhms());
        assertTrue(mgr.canUndoProperty().get());
        assertFalse(mgr.canRedoProperty().get());
    }

    @Test
    void undoRevertsAndRedoReapplies() {
        var initial = ProjectState.empty().withCircuit(simpleCircuit("a"));
        var mgr = newManager(initial);
        var properRes = Scope.field(
            Scope.indexed(
                Scope.indexed(Scope.root(), "circuits", circuitId("a")),
                "components", new ComponentId("R1")),
            "resistanceOhms");
        mgr.dispatch(Mutation.content(properRes, 100.0, 220.0, "Edit R"));
        assertEquals(220.0, ((CircuitComponent.Resistor) mgr.current()
            .circuit(circuitId("a")).components().get(0)).resistanceOhms());

        assertTrue(mgr.undoIn(mgr.any()));
        assertEquals(100.0, ((CircuitComponent.Resistor) mgr.current()
            .circuit(circuitId("a")).components().get(0)).resistanceOhms());
        assertFalse(mgr.canUndoProperty().get());
        assertTrue(mgr.canRedoProperty().get());

        assertTrue(mgr.redoIn(mgr.any()));
        assertEquals(220.0, ((CircuitComponent.Resistor) mgr.current()
            .circuit(circuitId("a")).components().get(0)).resistanceOhms());
    }

    @Test
    void undoInScopeIgnoresMutationsOutsideScope() {
        var c1 = simpleCircuit("a");
        var c2 = simpleCircuit("b");
        var mgr = newManager(ProjectState.empty().withCircuit(c1).withCircuit(c2));
        var resA = Scope.field(
            Scope.indexed(
                Scope.indexed(Scope.root(), "circuits", circuitId("a")),
                "components", new ComponentId("R1")),
            "resistanceOhms");
        var resB = Scope.field(
            Scope.indexed(
                Scope.indexed(Scope.root(), "circuits", circuitId("b")),
                "components", new ComponentId("R1")),
            "resistanceOhms");

        // Edit circuit A first, then B
        mgr.dispatch(Mutation.content(resA, 100.0, 200.0, "A"));
        mgr.dispatch(Mutation.content(resB, 100.0, 300.0, "B"));

        // Undo only within circuit-a's scope: should revert A's edit (the older one),
        // leaving B's edit intact.
        var aScope = Scope.indexed(Scope.root(), "circuits", circuitId("a"));
        assertTrue(mgr.undoIn(mgr.withinScope(aScope)));
        assertEquals(100.0, ((CircuitComponent.Resistor) mgr.current()
            .circuit(circuitId("a")).components().get(0)).resistanceOhms(),
            "A should be reverted");
        assertEquals(300.0, ((CircuitComponent.Resistor) mgr.current()
            .circuit(circuitId("b")).components().get(0)).resistanceOhms(),
            "B should remain edited");
    }

    @Test
    void transactionCoalescesIntoOneUndoEntry() {
        var initial = ProjectState.empty().withCircuit(simpleCircuit("a"));
        var mgr = newManager(initial);
        var rScope = Scope.indexed(
            Scope.indexed(Scope.root(), "circuits", circuitId("a")),
            "components", new ComponentId("R1"));

        try (var tx = mgr.beginTransaction("Tweak R", "test", rScope)) {
            // simulate three intermediate updates during a "drag"
            tx.<CircuitComponent.Resistor>apply(r -> new CircuitComponent.Resistor(r.id(), r.name(), 110));
            tx.<CircuitComponent.Resistor>apply(r -> new CircuitComponent.Resistor(r.id(), r.name(), 120));
            tx.<CircuitComponent.Resistor>apply(r -> new CircuitComponent.Resistor(r.id(), r.name(), 130));
        }
        // After auto-commit, exactly one undo entry exists.
        assertEquals(1, mgr.undoSnapshot().size());
        assertEquals(130.0, ((CircuitComponent.Resistor) mgr.current()
            .circuit(circuitId("a")).components().get(0)).resistanceOhms());

        // Single undo reverts the whole drag.
        assertTrue(mgr.undoIn(mgr.any()));
        assertEquals(100.0, ((CircuitComponent.Resistor) mgr.current()
            .circuit(circuitId("a")).components().get(0)).resistanceOhms());
    }

    @Test
    void refIntegrityCascadesOnSimConfigDelete() {
        var c = simpleCircuit("c");
        var sim = new SimulationConfigDocument(simId("a"), "Sim",
            SimulationConfig.fromPhysics(PhysicsParams.DEFAULTS, 0.05, c.id()));
        var seq = new SequenceDocument(seqId("s"), "Seq",
            new ax.xz.mri.model.sequence.ClipSequence(10.0, 1000.0, List.of(), List.of()),
            sim.id(), null);
        var initial = ProjectState.empty().withCircuit(c).withSimulation(sim).withSequence(seq);
        var mgr = newManager(initial);

        // Delete the simconfig — sequence's activeSimConfigId should cascade-clear.
        var simScope = Scope.indexed(Scope.root(), "simulations", sim.id());
        mgr.dispatch(new Mutation(simScope, sim, null, "Delete sim",
            java.time.Instant.now(), null, Mutation.Category.STRUCTURAL));
        assertNull(mgr.current().simulation(sim.id()));
        assertNull(mgr.current().sequence(seq.id()).activeSimConfigId());

        // Undo restores both atomically.
        assertTrue(mgr.undoIn(mgr.any()));
        assertNotNull(mgr.current().simulation(sim.id()));
        assertEquals(sim.id(), mgr.current().sequence(seq.id()).activeSimConfigId());
    }

    @Test
    void documentEditorScopesUndoToItsScope() {
        var c1 = simpleCircuit("a");
        var c2 = simpleCircuit("b");
        var mgr = newManager(ProjectState.empty().withCircuit(c1).withCircuit(c2));

        var editorA = new DocumentEditor<>(mgr,
            Scope.indexed(Scope.root(), "circuits", circuitId("a")),
            "schematic-a", CircuitDocument.class);
        var editorB = new DocumentEditor<>(mgr,
            Scope.indexed(Scope.root(), "circuits", circuitId("b")),
            "schematic-b", CircuitDocument.class);

        editorA.apply(d -> d.withName("Renamed A"), "rename");
        editorB.apply(d -> d.withName("Renamed B"), "rename");

        // Editor A's undo should revert A only.
        editorA.undo();
        assertEquals("Circuit-a", mgr.current().circuit(circuitId("a")).name());
        assertEquals("Renamed B", mgr.current().circuit(circuitId("b")).name(),
            "B's edit should be untouched");
    }

    @Test
    void diskRoundTripPreservesProjectState(@org.junit.jupiter.api.io.TempDir Path tmp) throws IOException {
        var c = simpleCircuit("a");
        var ef = new EigenfieldDocument(efId("z"), "EF", "desc", "1.0", "T");
        var initial = ProjectState.empty().withCircuit(c).withEigenfield(ef);
        var io = new ProjectStateIO();
        io.write(initial, tmp);
        var loaded = io.read(tmp);
        assertEquals(c, loaded.circuit(c.id()));
        assertEquals(ef, loaded.eigenfield(ef.id()));
    }
}
