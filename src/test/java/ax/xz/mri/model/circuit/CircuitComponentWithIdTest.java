package ax.xz.mri.model.circuit;

import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.project.ProjectNodeId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Every {@link CircuitComponent} subtype implements {@code withId} so the
 * schematic's duplicate action doesn't need a type-switch. This verifies
 * the cloned record keeps every other field and only swaps the id.
 */
class CircuitComponentWithIdTest {

    private static final ComponentId OLD = new ComponentId("old");
    private static final ComponentId NEW = new ComponentId("new");

    @Test
    void everySubtypeSwapsItsIdWithoutChangingOtherFields() {
        List<CircuitComponent> originals = List.of(
            new CircuitComponent.VoltageSource(OLD, "src",
                AmplitudeKind.QUADRATURE, 100_000, -1, 1, 50),
            new CircuitComponent.SwitchComponent(OLD, "sw", 1e-6, 1e9, 0.5, true),
            new CircuitComponent.Multiplexer(OLD, "mux", 1e-6, 1e9, 0.5),
            new CircuitComponent.Coil(OLD, "coil", new ProjectNodeId("ef"), 2e-3, 4.5),
            new CircuitComponent.Probe(OLD, "probe", 1.5, 90, 1e6),
            new CircuitComponent.Resistor(OLD, "r", 42),
            new CircuitComponent.Capacitor(OLD, "c", 2.2e-9),
            new CircuitComponent.Inductor(OLD, "l", 1.7e-6),
            new CircuitComponent.ShuntResistor(OLD, "rshunt", 75),
            new CircuitComponent.ShuntCapacitor(OLD, "cshunt", 3.3e-9),
            new CircuitComponent.ShuntInductor(OLD, "lshunt", 4.4e-6),
            new CircuitComponent.IdealTransformer(OLD, "xfmr", 2.5),
            new CircuitComponent.Mixer(OLD, "dc", 655_000)
        );

        for (var original : originals) {
            var copy = original.withId(NEW);
            assertNotSame(original, copy,
                original.getClass().getSimpleName() + ".withId must return a new instance");
            assertEquals(NEW, copy.id(),
                original.getClass().getSimpleName() + ".withId must set the new id");
            assertEquals(original.name(), copy.name(),
                original.getClass().getSimpleName() + ".withId must preserve the name");
            assertEquals(original.ports(), copy.ports(),
                original.getClass().getSimpleName() + ".withId must preserve the ports");
            // Structural equality minus the id: swap the id back, compare.
            assertEquals(original, copy.withId(OLD),
                original.getClass().getSimpleName() + ".withId must preserve every other field");
        }
    }
}
