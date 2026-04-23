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
    void emptyConstructor_includesGround() {
        var doc = CircuitDocument.empty(new ProjectNodeId("c-1"), "Empty");
        assertEquals(1, doc.components().size());
        assertInstanceOf(CircuitComponent.Ground.class, doc.components().get(0));
        assertTrue(doc.wires().isEmpty());
    }

    @Test
    void missingGround_isRejected() {
        var src = new CircuitComponent.VoltageSource(new ComponentId("s"), "S",
            AmplitudeKind.REAL, 0, 0, 1, 0);
        assertThrows(IllegalArgumentException.class, () ->
            new CircuitDocument(new ProjectNodeId("c-2"), "c", List.of(src), List.of(), CircuitLayout.empty()));
    }

    @Test
    void duplicateComponentId_isRejected() {
        var a = new CircuitComponent.Ground(new ComponentId("gnd"), "A");
        var b = new CircuitComponent.Ground(new ComponentId("gnd"), "B");
        assertThrows(IllegalArgumentException.class, () ->
            new CircuitDocument(new ProjectNodeId("c-3"), "c", List.of(a, b), List.of(), CircuitLayout.empty()));
    }

    @Test
    void duplicateComponentName_isRejected() {
        var a = new CircuitComponent.Ground(new ComponentId("g1"), "GND");
        var b = new CircuitComponent.Ground(new ComponentId("g2"), "GND");
        assertThrows(IllegalArgumentException.class, () ->
            new CircuitDocument(new ProjectNodeId("c-4"), "c", List.of(a, b), List.of(), CircuitLayout.empty()));
    }

    @Test
    void wireReferencingUnknownComponent_isRejected() {
        var gnd = new CircuitComponent.Ground(new ComponentId("gnd"), "GND");
        var badWire = new Wire("w-bad",
            new ComponentTerminal(new ComponentId("nope"), "a"),
            new ComponentTerminal(gnd.id(), "a"));
        assertThrows(IllegalArgumentException.class, () ->
            new CircuitDocument(new ProjectNodeId("c-5"), "c", List.of(gnd), List.of(badWire), CircuitLayout.empty()));
    }

    @Test
    void wireReferencingUnknownPort_isRejected() {
        var gnd = new CircuitComponent.Ground(new ComponentId("gnd"), "GND");
        var src = new CircuitComponent.VoltageSource(new ComponentId("s"), "S",
            AmplitudeKind.REAL, 0, 0, 1, 0);
        var badWire = new Wire("w-bad",
            new ComponentTerminal(src.id(), "never"),
            new ComponentTerminal(gnd.id(), "a"));
        assertThrows(IllegalArgumentException.class, () ->
            new CircuitDocument(new ProjectNodeId("c-6"), "c",
                List.of(src, gnd), List.of(badWire), CircuitLayout.empty()));
    }

    @Test
    void selfWire_isRejected() {
        var gnd = new CircuitComponent.Ground(new ComponentId("gnd"), "GND");
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "C", null, 0, 0);
        var selfWire = new Wire("w-self",
            new ComponentTerminal(coil.id(), "a"),
            new ComponentTerminal(coil.id(), "b"));
        assertThrows(IllegalArgumentException.class, () ->
            new CircuitDocument(new ProjectNodeId("c-7"), "c",
                List.of(coil, gnd), List.of(selfWire), CircuitLayout.empty()));
    }

    @Test
    void addComponent_appendsAndPreservesImmutability() {
        var doc = CircuitDocument.empty(new ProjectNodeId("c-8"), "C");
        var src = new CircuitComponent.VoltageSource(new ComponentId("s"), "S",
            AmplitudeKind.REAL, 0, 0, 1, 0);
        var next = doc.addComponent(src, new ComponentPosition(src.id(), 100, 100, 0));
        assertEquals(1, doc.components().size(), "Original doc is unchanged");
        assertEquals(2, next.components().size());
        assertTrue(next.layout().positionOf(src.id()).isPresent());
    }

    @Test
    void removeComponent_alsoStripsWiresAndLayout() {
        var gnd = new CircuitComponent.Ground(new ComponentId("gnd"), "GND");
        var src = new CircuitComponent.VoltageSource(new ComponentId("s"), "S",
            AmplitudeKind.REAL, 0, 0, 1, 0);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "C", null, 0, 0);
        var wA = new Wire("wa", new ComponentTerminal(src.id(), "out"), new ComponentTerminal(coil.id(), "a"));
        var wC = new Wire("wc", new ComponentTerminal(coil.id(), "b"), new ComponentTerminal(gnd.id(), "a"));
        var layout = CircuitLayout.empty()
            .with(new ComponentPosition(src.id(), 0, 0, 0))
            .with(new ComponentPosition(coil.id(), 100, 0, 0))
            .with(new ComponentPosition(gnd.id(), 200, 0, 0));
        var doc = new CircuitDocument(new ProjectNodeId("c-9"), "c",
            List.of(src, coil, gnd), List.of(wA, wC), layout);

        var stripped = doc.removeComponent(coil.id());
        assertFalse(stripped.components().stream().anyMatch(c -> c.id().equals(coil.id())));
        assertTrue(stripped.wires().stream().noneMatch(w ->
            w.from().componentId().equals(coil.id()) || w.to().componentId().equals(coil.id())));
        assertFalse(stripped.layout().positionOf(coil.id()).isPresent());
    }

    @Test
    void jsonRoundTripPreservesComponentsAndWires() throws Exception {
        var mapper = new ObjectMapper();
        var gnd = new CircuitComponent.Ground(new ComponentId("gnd"), "GND");
        var src = new CircuitComponent.VoltageSource(new ComponentId("src"), "RF",
            AmplitudeKind.QUADRATURE, 63e6, 0, 200e-6, 0);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "RF Coil",
            new ProjectNodeId("ef-rf"), 0, 0);
        var wires = List.of(
            new Wire("w1", new ComponentTerminal(src.id(), "out"), new ComponentTerminal(coil.id(), "a")),
            new Wire("w3", new ComponentTerminal(coil.id(), "b"), new ComponentTerminal(gnd.id(), "a"))
        );
        var original = new CircuitDocument(new ProjectNodeId("c-10"), "Round-trip",
            List.of(src, coil, gnd), wires, CircuitLayout.empty()
                .with(new ComponentPosition(src.id(), 10, 20, 0))
                .with(new ComponentPosition(coil.id(), 100, 20, 0))
                .with(new ComponentPosition(gnd.id(), 200, 20, 0)));

        String json = mapper.writeValueAsString(original);
        var restored = mapper.readValue(json, CircuitDocument.class);
        assertEquals(original, restored);
    }
}
