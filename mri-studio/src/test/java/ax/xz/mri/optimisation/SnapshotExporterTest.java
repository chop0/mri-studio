package ax.xz.mri.optimisation;

import ax.xz.mri.model.simulation.SignalTrace;
import ax.xz.mri.service.io.BlochDataReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotExporterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesViewerCompatibleOutputScenario() throws Exception {
        var data = OptimisationTestSupport.sampleDocument();
        var builder = new ScenarioBuilder();
        var request = builder.buildRequest(
            data,
            new ScenarioBuilder.BuildOptions(
                "Full GRAPE",
                "3",
                "Full GRAPE Java",
                ObjectiveMode.FULL_TRAIN,
                0,
                FreeMaskMode.ALL,
                4,
                2,
                1,
                1
            )
        );
        var snapshots = new LinkedHashMap<Integer, java.util.List<ax.xz.mri.model.sequence.PulseSegment>>();
        snapshots.put(0, request.parameterisation().expandSegments(request.initialSegments()));
        snapshots.put(2, OptimisationTestSupport.pulseB());
        var result = new OptimisationResult(
            request.initialSegments(),
            request.parameterisation().expandSegments(request.initialSegments()),
            new SnapshotSeries(snapshots),
            new SignalTrace(java.util.List.of(new SignalTrace.Point(0.0, 0.0))),
            2,
            2,
            -1.0,
            true,
            "ok"
        );
        File output = tempDir.resolve("out.json").toFile();

        new SnapshotExporter().write(output, data, request, result);
        var reloaded = BlochDataReader.read(output);

        assertTrue(reloaded.scenarios().containsKey("Full GRAPE"));
        assertTrue(reloaded.scenarios().containsKey("Full GRAPE Java"));
        assertEquals(java.util.List.of("0", "2"), reloaded.scenarios().get("Full GRAPE Java").iterationKeys());
    }
}
