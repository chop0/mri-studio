package ax.xz.mri.state;

import ax.xz.mri.model.nv.NvArrayGeometry;
import ax.xz.mri.model.nv.NvArrayShape;
import ax.xz.mri.model.nv.NvAxis;
import ax.xz.mri.model.nv.NvPhysics;
import ax.xz.mri.model.substance.ContinuousMagnetisation;
import ax.xz.mri.model.substance.NvEnsemble;
import ax.xz.mri.project.ProjectManifest;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SubstanceDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration: write a project containing a {@link SubstanceDocument} that wraps
 * an {@link NvEnsemble}, read it back through {@link ProjectStateIO}, verify
 * the substance survives the full disk round-trip including the polymorphic
 * Jackson {@code @kind} discriminator on {@link ax.xz.mri.model.substance.Substance}.
 */
class SubstanceProjectIoTest {

    @TempDir
    Path tempDir;

    @Test
    void nvEnsembleSurvivesFullProjectIoRoundTrip() throws Exception {
        var doc = sampleNvDoc();
        var map = new LinkedHashMap<ProjectNodeId, SubstanceDocument>();
        map.put(doc.id(), doc);

        var state = new ProjectState(
            new ProjectManifest("Substance-test", ".mri-studio/layout.json", ".mri-studio/ui-state.json"),
            java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
            java.util.Map.of(), java.util.Map.of(),
            map,
            java.util.Map.of());

        var io = new ProjectStateIO();
        io.write(state, tempDir);

        var restored = io.read(tempDir);
        assertEquals(1, restored.substances().size(), "expected exactly one substance on disk");
        assertEquals(doc, restored.substance(doc.id()));
    }

    @Test
    void continuousMagnetisationSurvivesFullProjectIoRoundTrip() throws Exception {
        var doc = new SubstanceDocument(
            new ProjectNodeId("sub-cm"),
            "Water (¹H)",
            ContinuousMagnetisation.defaults());
        var map = new LinkedHashMap<ProjectNodeId, SubstanceDocument>();
        map.put(doc.id(), doc);

        var state = new ProjectState(
            new ProjectManifest("CM-test", ".mri-studio/layout.json", ".mri-studio/ui-state.json"),
            java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
            java.util.Map.of(), java.util.Map.of(),
            map,
            java.util.Map.of());

        var io = new ProjectStateIO();
        io.write(state, tempDir);
        var restored = io.read(tempDir);

        assertEquals(1, restored.substances().size());
        assertEquals(doc, restored.substance(doc.id()));
    }

    private static SubstanceDocument sampleNvDoc() {
        var geom = new NvArrayGeometry(NvArrayShape.LINEAR_X_RANDOM, 16, 1e-6, 50e-9, NvAxis.AXIS_PLUS_Z, 7L);
        var ensemble = new NvEnsemble(geom, NvPhysics.defaults(), 0L, 0.0);
        return new SubstanceDocument(new ProjectNodeId("sub-io-test"), "Disk test", ensemble);
    }
}
