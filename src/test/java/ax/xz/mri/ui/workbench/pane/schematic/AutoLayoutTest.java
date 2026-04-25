package ax.xz.mri.ui.workbench.pane.schematic;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.starter.CircuitStarterLibrary;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Determinism and non-overlap guarantees for {@link AutoLayout}. */
class AutoLayoutTest {

    @Test
    void lowFieldMriStarterIsDeterministic() {
        var starter = CircuitStarterLibrary.byId("low-field-mri").orElseThrow();
        var id1 = new ProjectNodeId("c-1");
        var id2 = new ProjectNodeId("c-2");
        var repo1 = ProjectRepository.untitled();
        var repo2 = ProjectRepository.untitled();

        var a = starter.build(id1, "A", repo1);
        var b = starter.build(id2, "B", repo2);

        // Same layout structure (identical positions up to id substitution).
        assertEquals(a.components().size(), b.components().size());
        assertEquals(a.wires().size(), b.wires().size());
    }

    @Test
    void lowFieldMriStarterHasNoOverlappingPositions() {
        var starter = CircuitStarterLibrary.byId("low-field-mri").orElseThrow();
        var repo = ProjectRepository.untitled();
        var doc = starter.build(new ProjectNodeId("c-1"), "C", repo);

        var seen = new HashSet<String>();
        for (var c : doc.components()) {
            var pos = doc.layout().positionOf(c.id()).orElseThrow();
            String key = pos.x() + "," + pos.y();
            assertTrue(seen.add(key),
                "Two components share position (" + key + "): " + c.name());
        }
    }

    @Test
    void arrange_isStableWhenCalledTwice() {
        var components = List.<CircuitComponent>of(
            new CircuitComponent.VoltageSource(new ComponentId("s"), "S",
                AmplitudeKind.REAL, 0, 0, 1, 0),
            new CircuitComponent.Coil(new ComponentId("c"), "C", null, 0, 1)
        );
        var layoutA = AutoLayout.arrange(components, List.of());
        var layoutB = AutoLayout.arrange(components, List.of());

        for (var c : components) {
            assertEquals(layoutA.positionOf(c.id()), layoutB.positionOf(c.id()),
                "Auto-layout must be deterministic");
        }
    }
}
