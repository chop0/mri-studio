package ax.xz.mri.state;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentPosition;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.Wire;
import ax.xz.mri.project.ProjectNodeId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordSurgeryTest {
    private final RecordSurgery surgery = new RecordSurgery();

    @Test
    void withReplacesOneComponent() {
        var w = new Wire("w-1",
            new ComponentTerminal(new ComponentId("a"), "out"),
            new ComponentTerminal(new ComponentId("b"), "in"));

        var renamed = surgery.with(w, "id", "w-2");
        assertEquals("w-2", renamed.id());
        assertEquals(w.from(), renamed.from());
        assertEquals(w.to(), renamed.to());
    }

    @Test
    void withInvokesRecordCanonicalConstructorAndItsValidation() {
        // ComponentPosition normalises rotation modulo 4 in its compact constructor
        var pos = new ComponentPosition(new ComponentId("c"), 10, 20, 0, false);
        var rotated = surgery.with(pos, "rotationQuarters", 5);
        assertEquals(1, rotated.rotationQuarters(), "compact constructor must normalise rotation");
    }

    @Test
    void getReadsComponentByName() {
        var pos = new ComponentPosition(new ComponentId("c"), 10, 20, 0, false);
        assertEquals(10.0, surgery.get(pos, "x"));
        assertEquals(20.0, surgery.get(pos, "y"));
    }

    @Test
    void rebuildAtScopeReplacesNestedField() {
        var resistor = new CircuitComponent.Resistor(new ComponentId("R1"), "R1", 100.0);
        var doc = new CircuitDocument(
            new ProjectNodeId("circuit-a"), "Test",
            List.of(resistor),
            List.of(),
            CircuitLayout.empty()
        );
        // Scope: doc.components[R1].resistanceOhms — IndexedItem.parent is the doc (root).
        var indexed = Scope.indexed(Scope.root(), "components", new ComponentId("R1"));
        var resistanceField = Scope.field(indexed, "resistanceOhms");

        var rebuilt = (CircuitDocument) surgery.rebuild(doc, resistanceField, 220.0);
        var newR = (CircuitComponent.Resistor) rebuilt.components().get(0);
        assertEquals(220.0, newR.resistanceOhms());
        assertEquals("R1", newR.name());
    }

    @Test
    void getAtFollowsScopePath() {
        var resistor = new CircuitComponent.Resistor(new ComponentId("R1"), "R1", 100.0);
        var doc = new CircuitDocument(
            new ProjectNodeId("circuit-a"), "Test",
            List.of(resistor),
            List.of(),
            CircuitLayout.empty()
        );
        var indexed = Scope.indexed(Scope.root(), "components", new ComponentId("R1"));
        assertEquals(resistor, surgery.getAt(doc, indexed));
        assertEquals(100.0, surgery.getAt(doc, Scope.field(indexed, "resistanceOhms")));
    }

    @Test
    void rebuildOnIndexedItemRemoveDeletesElement() {
        var r1 = new CircuitComponent.Resistor(new ComponentId("R1"), "R1", 100.0);
        var r2 = new CircuitComponent.Resistor(new ComponentId("R2"), "R2", 200.0);
        var doc = new CircuitDocument(
            new ProjectNodeId("circuit-a"), "Test",
            List.of(r1, r2),
            List.of(),
            CircuitLayout.empty()
        );
        var indexed = Scope.indexed(Scope.root(), "components", new ComponentId("R1"));

        var rebuilt = (CircuitDocument) surgery.rebuild(doc, indexed, null);
        assertEquals(1, rebuilt.components().size());
        assertEquals("R2", rebuilt.components().get(0).name());
    }

    @Test
    void diffProducesFineGrainedMutations() {
        var r1 = new CircuitComponent.Resistor(new ComponentId("R1"), "R1", 100.0);
        var r1updated = new CircuitComponent.Resistor(new ComponentId("R1"), "R1", 220.0);
        var docBefore = new CircuitDocument(new ProjectNodeId("c"), "T",
            List.of(r1), List.of(), CircuitLayout.empty());
        var docAfter = new CircuitDocument(new ProjectNodeId("c"), "T",
            List.of(r1updated), List.of(), CircuitLayout.empty());

        var muts = surgery.diff(docBefore, docAfter, Scope.root(), "Edit", "test", Mutation.Category.CONTENT);
        assertEquals(1, muts.size(), "expected exactly one fine-grained mutation");
        var m = muts.get(0);
        assertTrue(m.scope().toString().contains("resistanceOhms"),
            "scope was: " + m.scope());
        assertEquals(100.0, m.before());
        assertEquals(220.0, m.after());
    }

    @Test
    void diffOnEqualReturnsEmpty() {
        var pos = new ComponentPosition(new ComponentId("c"), 1, 2, 0, false);
        var muts = surgery.diff(pos, pos, Scope.root(), "Noop", null, Mutation.Category.CONTENT);
        assertEquals(0, muts.size());
    }

    @Test
    void rebuildRoundTripsThroughDiff() {
        var pos1 = new ComponentPosition(new ComponentId("c"), 1, 2, 0, false);
        var pos2 = new ComponentPosition(new ComponentId("c"), 1, 5, 0, false);
        var muts = surgery.diff(pos1, pos2, Scope.root(), "move", null, Mutation.Category.CONTENT);
        assertEquals(1, muts.size());
        var rebuilt = surgery.rebuild(pos1, muts.get(0).scope(), muts.get(0).after());
        assertEquals(pos2, rebuilt);
        // Inverse direction
        var reverted = surgery.rebuild(rebuilt, muts.get(0).scope(), muts.get(0).before());
        assertEquals(pos1, reverted);
    }

    @Test
    void getMissingFieldThrows() {
        var pos = new ComponentPosition(new ComponentId("c"), 1, 2, 0, false);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> surgery.get(pos, "nonexistent"));
    }

    @Test
    void getAtNonExistentIndexedItemReturnsNull() {
        var doc = new CircuitDocument(new ProjectNodeId("c"), "T",
            List.of(), List.of(), CircuitLayout.empty());
        var indexed = Scope.indexed(Scope.root(), "components", new ComponentId("missing"));
        assertNull(surgery.getAt(doc, indexed));
    }
}
