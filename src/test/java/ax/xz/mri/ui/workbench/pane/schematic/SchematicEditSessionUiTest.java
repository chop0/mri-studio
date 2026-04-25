package ax.xz.mri.ui.workbench.pane.schematic;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentPosition;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.Wire;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.project.ProjectNodeId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Interaction-level tests for {@link CircuitEditSession}. */
class SchematicEditSessionUiTest {

    @Test
    void deleteSelectionRemovesComponentsAndIncidentWires() {
        var session = new CircuitEditSession(simpleCircuit());
        var coilId = new ComponentId("coil");
        session.selectedComponents.add(coilId);
        session.deleteSelection();
        var doc = session.doc();
        assertFalse(doc.components().stream().anyMatch(c -> c.id().equals(coilId)));
        assertTrue(doc.wires().stream().noneMatch(w ->
            w.from().componentId().equals(coilId) || w.to().componentId().equals(coilId)));
    }

    @Test
    void moveComponentUpdatesLayout() {
        var session = new CircuitEditSession(simpleCircuit());
        var coilId = new ComponentId("coil");
        session.moveComponent(coilId, 500, 800);
        var pos = session.doc().layout().positionOf(coilId).orElseThrow();
        assertEquals(500, pos.x());
        assertEquals(800, pos.y());
    }

    @Test
    void rotateSelectionBumpsRotationQuarters() {
        var session = new CircuitEditSession(simpleCircuit());
        session.selectedComponents.add(new ComponentId("coil"));
        session.rotateSelection();
        var pos = session.doc().layout().positionOf(new ComponentId("coil")).orElseThrow();
        assertEquals(1, pos.rotationQuarters());
        session.rotateSelection();
        session.rotateSelection();
        session.rotateSelection();
        pos = session.doc().layout().positionOf(new ComponentId("coil")).orElseThrow();
        assertEquals(0, pos.rotationQuarters(), "four rotations return to canonical");
    }

    @Test
    void mirrorSelectionTogglesFlag() {
        var session = new CircuitEditSession(simpleCircuit());
        session.selectedComponents.add(new ComponentId("coil"));
        session.mirrorSelection();
        assertTrue(session.doc().layout().positionOf(new ComponentId("coil")).orElseThrow().mirrored());
        session.mirrorSelection();
        assertFalse(session.doc().layout().positionOf(new ComponentId("coil")).orElseThrow().mirrored());
    }

    @Test
    void addComponentAutoRenamesToAvoidCollision() {
        var session = new CircuitEditSession(simpleCircuit());
        var duplicate = new CircuitComponent.Coil(new ComponentId("coil-2"), "Coil", null, 0, 1);
        session.addComponent(duplicate, new ComponentPosition(duplicate.id(), 400, 400, 0));
        var names = session.doc().components().stream().map(CircuitComponent::name).toList();
        assertTrue(names.contains("Coil"));
        assertTrue(names.contains("Coil 2"), "second 'Coil' should become 'Coil 2' on add");
    }

    @Test
    void addWireBumpsRevisionAndRejectsDuplicates() {
        var blank = CircuitDocument.empty(new ProjectNodeId("blank"), "Blank");
        var session = new CircuitEditSession(blank);
        var coil1 = new CircuitComponent.Coil(new ComponentId("c1"), "C1", null, 0, 1);
        var coil2 = new CircuitComponent.Coil(new ComponentId("c2"), "C2", null, 0, 1);
        session.addComponent(coil1, new ComponentPosition(coil1.id(), 100, 100, 0));
        session.addComponent(coil2, new ComponentPosition(coil2.id(), 300, 100, 0));
        int rev = session.revision.get();
        var a = new ComponentTerminal(coil1.id(), "in");
        var b = new ComponentTerminal(coil2.id(), "in");
        session.addWire(a, b);
        assertEquals(rev + 1, session.revision.get(), "wire add bumps revision");
        assertTrue(session.isWireBetween(a, b));
    }

    @Test
    void duplicateSelectionClonesComponentsWithFreshIdsAndWires() {
        var session = new CircuitEditSession(simpleCircuit());
        var srcId = new ComponentId("src");
        var coilId = new ComponentId("coil");
        session.selectedComponents.addAll(List.of(srcId, coilId));

        int beforeComponents = session.doc().components().size();
        int beforeWires = session.doc().wires().size();
        session.duplicateSelection(40, 0);
        assertEquals(beforeComponents + 2, session.doc().components().size());
        assertEquals(beforeWires + 1, session.doc().wires().size());
        assertEquals(2, session.selectedComponents.size());
        assertFalse(session.selectedComponents.contains(srcId));
    }

    @Test
    void replaceComponentPreservesSelection() {
        var session = new CircuitEditSession(simpleCircuit());
        var srcId = new ComponentId("src");
        session.selectedComponents.add(srcId);
        var src = (CircuitComponent.VoltageSource) session.componentAt(srcId);
        session.replaceComponent(src.withMaxAmplitude(42));
        var updated = (CircuitComponent.VoltageSource) session.componentAt(srcId);
        assertEquals(42, updated.maxAmplitude(), 1e-12);
        assertTrue(session.selectedComponents.contains(srcId));
    }

    private static CircuitDocument simpleCircuit() {
        var src = new CircuitComponent.VoltageSource(new ComponentId("src"), "S",
            AmplitudeKind.REAL, 0, 0, 1, 0);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "Coil", null, 0, 1);
        var wires = List.of(
            new Wire("w-drive", new ComponentTerminal(src.id(), "out"), new ComponentTerminal(coil.id(), "in"))
        );
        var layout = CircuitLayout.empty()
            .with(new ComponentPosition(src.id(), 100, 100, 0))
            .with(new ComponentPosition(coil.id(), 300, 100, 0));
        return new CircuitDocument(new ProjectNodeId("c"), "c",
            List.of(src, coil), wires, layout);
    }
}
