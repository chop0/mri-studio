package ax.xz.mri.project;

import ax.xz.mri.model.nv.NvArrayGeometry;
import ax.xz.mri.model.nv.NvArrayShape;
import ax.xz.mri.model.nv.NvAxis;
import ax.xz.mri.model.nv.NvPhysics;
import ax.xz.mri.model.substance.ContinuousMagnetisation;
import ax.xz.mri.model.substance.NvEnsemble;
import ax.xz.mri.model.substance.Substance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SubstanceDocumentTest {

    @TempDir
    Path tempDir;

    @Test
    void jsonRoundTripPreservesNvEnsemble() throws Exception {
        var doc = sampleNvDoc();
        var serialiser = new ProjectSerialiser();
        var path = tempDir.resolve("substance.json");
        serialiser.writeJson(path, doc);
        var restored = serialiser.readJson(path, SubstanceDocument.class);
        assertEquals(doc, restored);
        assertInstanceOf(NvEnsemble.class, restored.substance());
    }

    @Test
    void jsonRoundTripPreservesContinuousMagnetisation() throws Exception {
        var doc = new SubstanceDocument(
            new ProjectNodeId("sub-cm"),
            "Water (¹H)",
            ContinuousMagnetisation.defaults());
        var serialiser = new ProjectSerialiser();
        var path = tempDir.resolve("substance.json");
        serialiser.writeJson(path, doc);
        var restored = serialiser.readJson(path, SubstanceDocument.class);
        assertEquals(doc, restored);
        assertInstanceOf(ContinuousMagnetisation.class, restored.substance());
    }

    @Test
    void kindIsAlwaysSubstance() {
        assertEquals(ProjectNodeKind.SUBSTANCE, sampleNvDoc().kind());
    }

    @Test
    void withSubstanceReplacesSubstanceOnly() {
        var doc = sampleNvDoc();
        Substance replacement = ContinuousMagnetisation.defaults();
        var doc2 = doc.withSubstance(replacement);
        assertEquals(doc.id(), doc2.id());
        assertEquals(doc.name(), doc2.name());
        assertEquals(replacement, doc2.substance());
    }

    @Test
    void withNameReplacesNameOnly() {
        var doc = sampleNvDoc();
        var doc2 = doc.withName("Renamed");
        assertEquals("Renamed", doc2.name());
        assertEquals(doc.substance(), doc2.substance());
        assertEquals(doc.id(), doc2.id());
    }

    private static SubstanceDocument sampleNvDoc() {
        var geom = new NvArrayGeometry(NvArrayShape.LINEAR_X_RANDOM, 16, 1e-6, 50e-9, NvAxis.AXIS_PLUS_Z, 123L);
        var ensemble = new NvEnsemble(geom, NvPhysics.defaults(), 0L);
        return new SubstanceDocument(new ProjectNodeId("sub-test-1"), "Test NV Array", ensemble);
    }
}
