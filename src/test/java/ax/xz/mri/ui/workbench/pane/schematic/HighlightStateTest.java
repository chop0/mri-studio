package ax.xz.mri.ui.workbench.pane.schematic;

import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.project.ProjectNodeId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the path-highlight overlay added to {@link CircuitEditSession}.
 * The overlay is independent of selection — both can be active at once.
 */
class HighlightStateTest {

    @Test
    void setHighlightStoresComponentsAndWires() {
        var session = CircuitEditSession.standalone(empty());
        session.setHighlight(
            List.of(new ComponentId("a"), new ComponentId("b")),
            List.of("w1", "w2"));
        assertTrue(session.highlightedComponents.contains(new ComponentId("a")));
        assertTrue(session.highlightedComponents.contains(new ComponentId("b")));
        assertTrue(session.highlightedWires.contains("w1"));
        assertTrue(session.highlightedWires.contains("w2"));
    }

    @Test
    void setHighlightReplacesPreviousOverlay() {
        var session = CircuitEditSession.standalone(empty());
        session.setHighlight(List.of(new ComponentId("a")), List.of("w1"));
        session.setHighlight(List.of(new ComponentId("b")), List.of("w2"));
        assertFalse(session.highlightedComponents.contains(new ComponentId("a")),
            "previous components must be cleared on re-set");
        assertFalse(session.highlightedWires.contains("w1"),
            "previous wires must be cleared on re-set");
        assertTrue(session.highlightedComponents.contains(new ComponentId("b")));
        assertTrue(session.highlightedWires.contains("w2"));
    }

    @Test
    void clearHighlightDropsBothSets() {
        var session = CircuitEditSession.standalone(empty());
        session.setHighlight(List.of(new ComponentId("a")), List.of("w1"));
        session.clearHighlight();
        assertTrue(session.highlightedComponents.isEmpty());
        assertTrue(session.highlightedWires.isEmpty());
    }

    @Test
    void highlightIsIndependentOfSelection() {
        var session = CircuitEditSession.standalone(empty());
        var id = new ComponentId("a");
        session.selectOnly(id);
        session.setHighlight(List.of(new ComponentId("b")), List.of());
        // Selection unchanged.
        assertTrue(session.selectedComponents.contains(id));
        // Highlight is its own set.
        assertTrue(session.highlightedComponents.contains(new ComponentId("b")));
        assertFalse(session.highlightedComponents.contains(id));
    }

    @Test
    void setHighlightAcceptsNullCollections() {
        var session = CircuitEditSession.standalone(empty());
        session.setHighlight(null, null);
        assertTrue(session.highlightedComponents.isEmpty());
        assertTrue(session.highlightedWires.isEmpty());
    }

    private static CircuitDocument empty() {
        return CircuitDocument.empty(new ProjectNodeId("c"), "c");
    }
}
