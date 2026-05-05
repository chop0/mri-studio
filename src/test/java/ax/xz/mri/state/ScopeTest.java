package ax.xz.mri.state;

import ax.xz.mri.project.ProjectNodeId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeTest {

    @Test
    void rootContainsEverything() {
        var root = Scope.root();
        var circuits = Scope.field(root, "circuits");
        var oneCircuit = Scope.indexed(circuits, "circuits", new ProjectNodeId("circuit-x"));
        var nested = Scope.field(oneCircuit, "components");

        assertTrue(root.contains(root));
        assertTrue(root.contains(circuits));
        assertTrue(root.contains(oneCircuit));
        assertTrue(root.contains(nested));
    }

    @Test
    void siblingScopesDoNotContainEachOther() {
        var root = Scope.root();
        var circuits = Scope.indexed(Scope.field(root, "circuits"), "circuits", new ProjectNodeId("circuit-a"));
        var sequences = Scope.indexed(Scope.field(root, "sequences"), "sequences", new ProjectNodeId("seq-b"));

        assertFalse(circuits.contains(sequences));
        assertFalse(sequences.contains(circuits));
    }

    @Test
    void scopeWithSameKeyContainsItsDescendants() {
        var idA = new ProjectNodeId("circuit-a");
        var idB = new ProjectNodeId("circuit-b");
        var circuitA = Scope.indexed(Scope.field(Scope.root(), "circuits"), "circuits", idA);
        var circuitB = Scope.indexed(Scope.field(Scope.root(), "circuits"), "circuits", idB);
        var aComponents = Scope.field(circuitA, "components");

        assertTrue(circuitA.contains(aComponents));
        assertFalse(circuitB.contains(aComponents));
    }

    @Test
    void depthIncrements() {
        var s = Scope.root();
        org.junit.jupiter.api.Assertions.assertEquals(0, s.depth());
        s = Scope.field(s, "x");
        org.junit.jupiter.api.Assertions.assertEquals(1, s.depth());
        s = Scope.indexed(s, "x", "key");
        org.junit.jupiter.api.Assertions.assertEquals(2, s.depth());
    }
}
