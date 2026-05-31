package ax.xz.mri.state;

import ax.xz.mri.model.procedure.ProcedureStarterLibrary;
import ax.xz.mri.project.ProcedureDocument;
import ax.xz.mri.project.ProjectManifest;
import ax.xz.mri.project.ProjectNodeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Procedures live on disk as ordinary {@code procedures/&lt;Name&gt;.java}
 * files — this test pins the write/read round-trip plus the one-time
 * migration from the older {@code procedures/&lt;slug&gt;/procedure.json}
 * layout.
 */
final class ProcedureSourceOnDiskTest {

    @TempDir
    Path projectRoot;

    @Test
    void writesProcedureAsDotJavaAndOmitsProcedureJson() throws Exception {
        var source = ProcedureStarterLibrary.byId("blank").orElseThrow().source();
        var doc = new ProcedureDocument(new ProjectNodeId("proc-blank"),
            "Blank script", source);

        var state = ProjectState.empty()
            .withManifest(new ProjectManifest("Demo", ".mri-studio/layout.json", ".mri-studio/ui-state.json"))
            .withProcedure(doc);
        new ProjectStateIO().write(state, projectRoot);

        var javaFile = projectRoot.resolve("procedures/BlankScript.java");
        assertTrue(Files.isRegularFile(javaFile),
            "expected procedures/BlankScript.java to be written");
        assertEquals(source, Files.readString(javaFile));
        // No legacy procedure.json subdirectories should exist.
        try (var entries = Files.list(projectRoot.resolve("procedures"))) {
            var dirs = entries.filter(Files::isDirectory).toList();
            assertTrue(dirs.isEmpty(),
                "no per-procedure slug directories should remain on disk, found: " + dirs);
        }
    }

    @Test
    void readsProceduresBackFromDiskAndDerivesNameFromFilename() throws Exception {
        var source = ProcedureStarterLibrary.byId("blank").orElseThrow().source();
        var doc = new ProcedureDocument(new ProjectNodeId("proc-blank"),
            "Blank script", source);
        var state = ProjectState.empty()
            .withManifest(new ProjectManifest("Demo", ".mri-studio/layout.json", ".mri-studio/ui-state.json"))
            .withProcedure(doc);
        new ProjectStateIO().write(state, projectRoot);

        var reloaded = new ProjectStateIO().read(projectRoot);
        assertEquals(1, reloaded.procedures().size());
        var read = reloaded.procedures().values().iterator().next();
        assertEquals("BlankScript", read.name(), "name comes from the file basename");
        assertEquals(source, read.source());
    }

}
