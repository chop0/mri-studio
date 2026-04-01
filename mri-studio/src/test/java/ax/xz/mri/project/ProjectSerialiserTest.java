package ax.xz.mri.project;

import ax.xz.mri.support.TestBlochDataFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectSerialiserTest {
    @TempDir
    Path tempDir;

    @Test
    void manifestAndImportLinkRoundTrip() throws Exception {
        var serialiser = new ProjectSerialiser();
        var manifest = new ProjectManifest("Demo Project", ".mri-studio/layout.json", ".mri-studio/ui-state.json");
        var importLink = new ImportLinkDocument(new ProjectNodeId("import-1"), "Legacy Import", "/tmp/bloch_data.json", ReloadMode.MANUAL);

        var manifestPath = tempDir.resolve("mri-project.toml");
        var importPath = tempDir.resolve("imports/legacy.import.toml");

        serialiser.writeManifest(manifestPath, manifest);
        serialiser.writeImportLink(importPath, importLink);

        assertEquals(manifest, serialiser.readManifest(manifestPath));
        assertEquals(importLink, serialiser.readImportLink(importPath));
    }

    @Test
    void typedJsonRoundTripPreservesSequenceSnapshotStructure() throws Exception {
        var serialiser = new ProjectSerialiser();
        var snapshot = new SequenceSnapshotDocument(
            new ProjectNodeId("snapshot-1"),
            "Snapshot A",
            new ProjectNodeId("capture-1"),
            TestBlochDataFactory.sampleDocument().field().segments,
            TestBlochDataFactory.pulseA()
        );
        var path = tempDir.resolve("snapshot.json");

        serialiser.writeJson(path, snapshot);
        var restored = serialiser.readJson(path, SequenceSnapshotDocument.class);

        assertEquals(snapshot.id(), restored.id());
        assertEquals(snapshot.name(), restored.name());
        assertEquals(snapshot.parentCaptureId(), restored.parentCaptureId());
        assertEquals(snapshot.segments().size(), restored.segments().size());
        assertEquals(snapshot.pulse().size(), restored.pulse().size());
    }
}
