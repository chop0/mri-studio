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
    void jsonRoundTripPreservesEigenfieldDocument() throws Exception {
        var serialiser = new ProjectSerialiser();
        var eigen = new EigenfieldDocument(
            new ProjectNodeId("ef-1"), "E",
            "A uniform field", "return Vec3.of(0,0,1);", "T", 1.0);
        var path = tempDir.resolve("eigen.json");
        serialiser.writeJson(path, eigen);
        var restored = serialiser.readJson(path, EigenfieldDocument.class);
        assertEquals(eigen.id(), restored.id());
        assertEquals(eigen.name(), restored.name());
        assertEquals(eigen.script(), restored.script());
        assertEquals(eigen.units(), restored.units());
        assertEquals(eigen.defaultMagnitude(), restored.defaultMagnitude());
    }
}
