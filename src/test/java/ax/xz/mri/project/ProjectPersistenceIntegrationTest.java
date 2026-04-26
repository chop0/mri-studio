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
        var session = new ProjectSessionViewModel();
        var config = session.createSimConfig("Round-trip",
            SimConfigTemplate.LOW_FIELD_MRI, PhysicsParams.DEFAULTS);

        // Mutate the circuit: add a resistor, save again.
        var repo = session.repository.get();
        var circuit = repo.circuit(config.config().circuitId());
        var resistor = new CircuitComponent.Resistor(new ComponentId("r-extra"), "R extra", 50);
        var updated = circuit.addComponent(resistor, null);
        repo.updateCircuit(updated);

        session.saveProject(root);

        // Re-open into a fresh session.
        var reopened = new ProjectSessionViewModel();
        reopened.openProject(root);

        var reloadedRepo = reopened.repository.get();
        assertEquals(1, reloadedRepo.simConfigIds().size(), "One sim-config");
        assertEquals(1, reloadedRepo.circuitIds().size(), "One circuit");
        // Circuits load before sim-configs, so the config's pointer must resolve.
        var reloadedConfig = (SimulationConfigDocument) reloadedRepo.node(
            reloadedRepo.simConfigIds().get(0));
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
