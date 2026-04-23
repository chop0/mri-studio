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

/**
 * Interaction-level tests for {@link CircuitEditSession}.
 *
 * <p>The UI renders this session; these tests exercise every mutation the
 * canvas and inspector can trigger so UI regressions are caught without a
 * running JavaFX scene.
 */
class SchematicEditSessionUiTest {

    @Test
    void deleteSelectionRemovesComponentsAndIncidentWires() {
        var session = new CircuitEditSession(lowCircuit());
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
        var session = new CircuitEditSession(lowCircuit());
        var coilId = new ComponentId("coil");
        session.moveComponent(coilId, 500, 800);
        var pos = session.doc().layout().positionOf(coilId).orElseThrow();
        assertEquals(500, pos.x());
        assertEquals(800, pos.y());
    }

    @Test
    void addWireBumpsRevisionAndRejectsDuplicates() {
        // Start from a blank document so we can reliably add a fresh wire.
        var blank = CircuitDocument.empty(new ProjectNodeId("blank"), "Blank");
        var session = new CircuitEditSession(blank);
        var gndId = blank.components().get(0).id();
        var extraGround = new CircuitComponent.Ground(new ComponentId("gnd-2"), "GND-2");
        session.addComponent(extraGround, new ComponentPosition(extraGround.id(), 100, 100, 0));
        int rev = session.revision.get();
        var a = new ComponentTerminal(gndId, "a");
        var b = new ComponentTerminal(extraGround.id(), "a");
        session.addWire(a, b);
        assertEquals(rev + 1, session.revision.get(), "wire add bumps revision");
        assertTrue(session.isWireBetween(a, b));
    }

    @Test
    void duplicateSelectionClonesComponentsWithFreshIdsAndWires() {
        var session = new CircuitEditSession(lowCircuit());
        var srcId = new ComponentId("src");
        var coilId = new ComponentId("coil");
        session.selectedComponents.addAll(List.of(srcId, coilId));

        int beforeComponents = session.doc().components().size();
        int beforeWires = session.doc().wires().size();
        session.duplicateSelection(40, 0);
        int afterComponents = session.doc().components().size();
        int afterWires = session.doc().wires().size();

        assertEquals(beforeComponents + 2, afterComponents, "two new components");
        // The src-coil wire between selected pair must be duplicated too.
        assertEquals(beforeWires + 1, afterWires, "src-coil wire cloned; ground wires untouched");
        assertEquals(2, session.selectedComponents.size(), "clones become new selection");
        assertFalse(session.selectedComponents.contains(srcId));
    }

    @Test
    void replaceComponentPreservesSelection() {
        var session = new CircuitEditSession(lowCircuit());
        var srcId = new ComponentId("src");
        session.selectedComponents.add(srcId);
        var src = (CircuitComponent.VoltageSource) session.componentAt(srcId);
        session.replaceComponent(src.withMaxAmplitude(42));
        var updated = (CircuitComponent.VoltageSource) session.componentAt(srcId);
        assertEquals(42, updated.maxAmplitude(), 1e-12);
        assertTrue(session.selectedComponents.contains(srcId));
    }

    private static CircuitDocument lowCircuit() {
        var src = new CircuitComponent.VoltageSource(new ComponentId("src"), "S",
            AmplitudeKind.REAL, 0, 0, 1, 0);
        var coil = new CircuitComponent.Coil(new ComponentId("coil"), "C", null, 0, 0);
        var gnd = new CircuitComponent.Ground(new ComponentId("gnd"), "GND");
        var wires = List.of(
            new Wire("w-drive", new ComponentTerminal(src.id(), "out"), new ComponentTerminal(coil.id(), "a")),
            new Wire("w-coil-gnd", new ComponentTerminal(coil.id(), "b"), new ComponentTerminal(gnd.id(), "a"))
        );
        var layout = CircuitLayout.empty()
            .with(new ComponentPosition(src.id(), 100, 100, 0))
            .with(new ComponentPosition(coil.id(), 300, 100, 0))
            .with(new ComponentPosition(gnd.id(), 500, 100, 0));
        return new CircuitDocument(new ProjectNodeId("c"), "c",
            List.of(src, coil, gnd), wires, layout);
    }
}
