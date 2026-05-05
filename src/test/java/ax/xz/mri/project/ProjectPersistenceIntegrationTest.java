package ax.xz.mri.project;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.ui.wizard.starters.SimConfigTemplate;
import ax.xz.mri.model.simulation.PhysicsParams;
import ax.xz.mri.ui.viewmodel.ProjectSessionViewModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end persistence test. Creates a low-field MRI project, tweaks the
 * circuit, saves, reopens, and verifies every edit round-trips.
 */
class ProjectPersistenceIntegrationTest {

    @Test
    void lowFieldProjectRoundTrips(@TempDir Path root) throws Exception {
        var session = ProjectSessionViewModel.standalone();
        var config = session.createSimConfig("Round-trip",
            SimConfigTemplate.LOW_FIELD_MRI, PhysicsParams.DEFAULTS);

        // Mutate the circuit: add a resistor, save again.
        var circuit = session.project().circuit(config.config().circuitId());
        var resistor = new CircuitComponent.Resistor(new ComponentId("r-extra"), "R extra", 50);
        var updated = circuit.addComponent(resistor, null);
        var scope = ax.xz.mri.state.Scope.indexed(ax.xz.mri.state.Scope.root(), "circuits", updated.id());
        session.state().dispatch(new ax.xz.mri.state.Mutation(scope, circuit, updated,
            "Add resistor", java.time.Instant.now(), null,
            ax.xz.mri.state.Mutation.Category.CONTENT));

        session.saveProject(root);

        // Re-open into a fresh session.
        var reopened = ProjectSessionViewModel.standalone();
        reopened.openProject(root);

        var reloadedRepo = reopened.project();
        assertEquals(1, reloadedRepo.simulationIds().size(), "One sim-config");
        assertEquals(1, reloadedRepo.circuitIds().size(), "One circuit");
        // Circuits load before sim-configs, so the config's pointer must resolve.
        var reloadedConfig = (SimulationConfigDocument) reloadedRepo.node(
            reloadedRepo.simulationIds().get(0));
        assertNotNull(reloadedRepo.circuit(reloadedConfig.config().circuitId()),
            "Config's circuitId must resolve after reload");

        var reloadedCircuit = reloadedRepo.circuit(reloadedConfig.config().circuitId());
        assertTrue(reloadedCircuit.components().stream().anyMatch(c -> c.id().equals(resistor.id())),
            "Added resistor must survive round-trip");
        // Eigenfields referenced by coils round-trip too.
        for (var coil : reloadedCircuit.coils()) {
            assertNotNull(reloadedRepo.node(coil.eigenfieldId()),
                "Coil '" + coil.name() + "' references eigenfield " + coil.eigenfieldId()
                + " which must be loaded too");
        }
    }
}
