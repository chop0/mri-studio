package ax.xz.mri.optimisation.cli;

import ax.xz.mri.optimisation.OptimisationTestSupport;
import ax.xz.mri.service.io.BlochDataReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptimiserCliMainTest {
    @TempDir
    Path tempDir;

    @Test
    void cliCreatesOutputScenarioFile() throws Exception {
        File input = tempDir.resolve("input.json").toFile();
        File output = tempDir.resolve("output.json").toFile();
        OptimisationTestSupport.writeBlochDataJson(input, OptimisationTestSupport.sampleDocument());
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        int exitCode = OptimiserCliMain.run(new String[]{
            "--input", input.getAbsolutePath(),
            "--output", output.getAbsolutePath(),
            "--scenario", "Full GRAPE",
            "--objective", "periodic",
            "--prefix-segments", "1",
            "--mask-mode", "none",
            "--iterations", "3",
            "--snapshot-every", "1"
        }, new PrintStream(stdout), new PrintStream(stderr));

        assertEquals(0, exitCode);
        assertTrue(stdout.toString().contains("Optimising scenario 'Full GRAPE'"));
        assertTrue(output.isFile());
        var reloaded = BlochDataReader.read(output);
        assertTrue(reloaded.scenarios().containsKey("Full GRAPE Java"));
    }
}
