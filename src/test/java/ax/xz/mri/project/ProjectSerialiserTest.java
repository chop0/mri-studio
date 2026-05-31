package ax.xz.mri.project;

import ax.xz.mri.model.simulation.NvSimulationMethod;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.model.simulation.SimulationMethods;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectSerialiserTest {
    @TempDir
    Path tempDir;

    @Test
    void manifestRoundTrip() throws Exception {
        var serialiser = new ProjectSerialiser();
        var manifest = new ProjectManifest("Demo Project", ".mri-studio/layout.json", ".mri-studio/ui-state.json");
        var manifestPath = tempDir.resolve("mri-project.toml");
        serialiser.writeManifest(manifestPath, manifest);
        assertEquals(manifest, serialiser.readManifest(manifestPath));
    }

    @Test
    void manifestRoundTripWithActiveSimulation() throws Exception {
        var serialiser = new ProjectSerialiser();
        var manifest = new ProjectManifest("Demo", ".mri-studio/layout.json",
            ".mri-studio/ui-state.json", "low-field-mri");
        var manifestPath = tempDir.resolve("mri-project.toml");
        serialiser.writeManifest(manifestPath, manifest);
        var restored = serialiser.readManifest(manifestPath);
        assertEquals("low-field-mri", restored.activeSimulation());
        assertEquals(manifest, restored);
    }

    @Test
    void manifestOmitsActiveSimulationLineWhenNull() throws Exception {
        var serialiser = new ProjectSerialiser();
        var manifest = new ProjectManifest("Demo", ".mri-studio/layout.json",
            ".mri-studio/ui-state.json", null);
        var manifestPath = tempDir.resolve("mri-project.toml");
        serialiser.writeManifest(manifestPath, manifest);
        var text = java.nio.file.Files.readString(manifestPath);
        org.junit.jupiter.api.Assertions.assertFalse(text.contains("active_simulation"),
            "active_simulation = … line must be omitted when null");
        assertEquals(manifest, serialiser.readManifest(manifestPath));
    }

    @Test
    void jsonRoundTripPreservesEigenfieldDocument() throws Exception {
        var serialiser = new ProjectSerialiser();
        var eigen = new EigenfieldDocument(
            new ProjectNodeId("ef-1"), "E",
            "A uniform field", "return Vec3.of(0,0,1);", "T");
        var path = tempDir.resolve("eigen.json");
        serialiser.writeJson(path, eigen);
        var restored = serialiser.readJson(path, EigenfieldDocument.class);
        assertEquals(eigen.id(), restored.id());
        assertEquals(eigen.name(), restored.name());
        assertEquals(eigen.script(), restored.script());
        assertEquals(eigen.units(), restored.units());
        assertEquals(eigen, restored);
    }

    @Test
    void jsonRoundTripPreservesNvSimulationMethod() throws Exception {
        var serialiser = new ProjectSerialiser();
        var method = new NvSimulationMethod.ClusteredQubitHamiltonian(3, 25e-9);
        var config = new SimulationConfig(0.01, 1e-9, new ProjectNodeId("circuit-1"),
            new SimulationMethods(method));
        var doc = new SimulationConfigDocument(new ProjectNodeId("simcfg-1"), "NV diamond", config);
        var path = tempDir.resolve("simcfg.json");
        serialiser.writeJson(path, doc);
        var restored = serialiser.readJson(path, SimulationConfigDocument.class);
        assertEquals(doc, restored);
        assertEquals(method, restored.config().methods().nv(),
            "Clustered-qubit method (cap + cutoff) must survive the JSON round-trip");
    }

    @Test
    void legacySimConfigWithoutMethodsLoadsAsIndependent() throws Exception {
        var serialiser = new ProjectSerialiser();
        var config = new SimulationConfig(0.01, 1e-9, new ProjectNodeId("circuit-1"));
        var doc = new SimulationConfigDocument(new ProjectNodeId("simcfg-2"), "Legacy", config);
        var path = tempDir.resolve("legacy.json");
        serialiser.writeJson(path, doc);
        // Strip the methods node to mimic a pre-Part-14 project file.
        var mapper = serialiser.mapper();
        var tree = mapper.readTree(path.toFile());
        ((ObjectNode) tree.get("config")).remove("methods");
        Files.writeString(path, mapper.writeValueAsString(tree));
        var restored = serialiser.readJson(path, SimulationConfigDocument.class);
        assertEquals(NvSimulationMethod.independent(), restored.config().methods().nv(),
            "A config file without a methods block must default to the independent NV technique");
    }
}
