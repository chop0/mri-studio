package ax.xz.mri.model.circuit;

import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.project.ProjectNodeId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Validation + JSON round-trip tests for {@link CircuitDocument}. */
class CircuitDocumentTest {

    @Test
    void emptyConstructor_isLiterallyEmpty() {
        var doc = CircuitDocument.empty(new ProjectNodeId("c-1"), "Empty");
        assertTrue(doc.components().isEmpty());
        assertTrue(doc.wires().isEmpty());
    }

    @Test
    void duplicateComponentId_isRejected() {
        var a = new CircuitComponent.Coil(new ComponentId("c0"), "A", null, 0, 0);
        var b = new CircuitComponent.Coil(new ComponentId("c0"), "B", null, 0, 0);
        assertThrows(IllegalArgumentException.class, () ->
            new CircuitDocument(new ProjectNodeId("c-3"), "c", List.of(a, b), List.of(), CircuitLayout.empty()));
    }

    @Test
    void duplicateComponentName_isRejected() {
        var a = new CircuitComponent.Coil(new ComponentId("c0"), "Same", null, 0, 0);
        var b = new CircuitComponent.Coil(new ComponentId("c1"), "Same", null, 0, 0);
        assertThrows(IllegalArgumentException.class, () ->
            new CircuitDocument(new ProjectNodeId("c-4"), "c", List.of(a, b), List.of(), CircuitLayout.empty()));
    }

    @Test
    void wireReferencingUnknownComponent_isRejected() {
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "C", null, 0, 0);
        var badWire = new Wire("w-bad",
            new ComponentTerminal(new ComponentId("nope"), "in"),
            new ComponentTerminal(coil.id(), "in"));
        assertThrows(IllegalArgumentException.class, () ->
            new CircuitDocument(new ProjectNodeId("c-5"), "c", List.of(coil), List.of(badWire), CircuitLayout.empty()));
    }

    @Test
    void wireReferencingUnknownPort_isRejected() {
        var coil = new CircuitComponent.Coil(new ComponentId("c"), "C", null, 0, 0);
        var src = new CircuitComponent.VoltageSource(new ComponentId("s"), "S",
            AmplitudeKind.REAL, 0, 0, 1, 0);
        var badWire = new Wire("w-bad",
            new ComponentTerminal(src.id(), "never"),
            new ComponentTerminal(coil.id(), "in"));
        assertThrows(IllegalArgumentException.class, () ->
            new CircuitDocument(new ProjectNodeId("c-6"), "c",
                List.of(src, coil), List.of(badWire), CircuitLayout.empty()));
    }

    @Test
    void selfWire_isRejected() {
        var sw = new CircuitComponent.SwitchComponent(new ComponentId("sw"), "SW", 0.5, 1e9, 0.5);
        var selfWire = new Wire("w-self",
            new ComponentTerminal(sw.id(), "a"),
            new ComponentTerminal(sw.id(), "b"));
        assertThrows(IllegalArgumentException.class, () ->
            new CircuitDocument(new ProjectNodeId("c-7"), "c",
                List.of(sw), List.of(selfWire), CircuitLayout.empty()));
    }

    @Test
    void addComponent_appendsAndPreservesImmutability() {
        var doc = CircuitDocument.empty(new ProjectNodeId("c-8"), "C");
        var src = new CircuitComponent.VoltageSource(new ComponentId("s"), "S",
            AmplitudeKind.REAL, 0, 0, 1, 0);
        var next = doc.addComponent(src, new ComponentPosition(src.id(), 100, 100, 0));
        assertEquals(0, doc.components().size(), "Original doc unchanged");
        assertEquals(1, next.components().size());
        assertTrue(next.layout().positionOf(src.id()).isPresent());
    }

    @Test
    void removeComponent_alsoStripsWiresAndLayout() {
        var src = new CircuitComponent.VoltageSource(new ComponentId("s"), "S",
            AmplitudeKind.REAL, 0, 0, 1, 0);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "C", null, 0, 0);
        var w = new Wire("w1", new ComponentTerminal(src.id(), "out"), new ComponentTerminal(coil.id(), "in"));
        var layout = CircuitLayout.empty()
            .with(new ComponentPosition(src.id(), 0, 0, 0))
            .with(new ComponentPosition(coil.id(), 100, 0, 0));
        var doc = new CircuitDocument(new ProjectNodeId("c-9"), "c",
            List.of(src, coil), List.of(w), layout);

        var stripped = doc.removeComponent(coil.id());
        assertFalse(stripped.components().stream().anyMatch(c -> c.id().equals(coil.id())));
        assertTrue(stripped.wires().isEmpty());
        assertFalse(stripped.layout().positionOf(coil.id()).isPresent());
    }

    @Test
    void jsonRoundTripPreservesComponentsAndWires() throws Exception {
        var mapper = new ObjectMapper();
        var src = new CircuitComponent.VoltageSource(new ComponentId("src"), "RF",
            AmplitudeKind.REAL, 63e6, 0, 200e-6, 0);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "RF Coil",
            new ProjectNodeId("ef-rf"), 0, 0);
        var wires = List.of(
            new Wire("w1", new ComponentTerminal(src.id(), "out"), new ComponentTerminal(coil.id(), "in"))
        );
        var original = new CircuitDocument(new ProjectNodeId("c-10"), "Round-trip",
            List.of(src, coil), wires, CircuitLayout.empty()
                .with(new ComponentPosition(src.id(), 10, 20, 0))
                .with(new ComponentPosition(coil.id(), 100, 20, 0)));

        String json = mapper.writeValueAsString(original);
        var restored = mapper.readValue(json, CircuitDocument.class);
        assertEquals(original, restored);
    }
}
