package ax.xz.mri.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
