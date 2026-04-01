package ax.xz.mri.optimisation.cli;

import ax.xz.mri.optimisation.CpuObjectiveEngine;
import ax.xz.mri.optimisation.FreeMaskMode;
import ax.xz.mri.optimisation.MaskedParameterOptimiser;
import ax.xz.mri.optimisation.ObjectiveMode;
import ax.xz.mri.optimisation.ScenarioBuilder;
import ax.xz.mri.optimisation.SnapshotExporter;
import ax.xz.mri.service.io.BlochDataReader;

import java.io.File;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

/** Command-line entrypoint for the Java optimiser. */
public final class OptimiserCliMain {
    private OptimiserCliMain() {
    }

    public static void main(String[] args) throws Exception {
        int exit = run(args, System.out, System.err);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    public static int run(String[] args, PrintStream out, PrintStream err) throws Exception {
        var options = parseArgs(args);
        if (!options.containsKey("input") || !options.containsKey("output") || !options.containsKey("scenario")) {
            err.println("Usage: --input <bloch_data.json> --output <out.json> --scenario <name> [--iteration <key>] " +
                "[--output-scenario <name>] [--objective full|periodic] [--prefix-segments <n>] " +
                "[--mask-mode all|refocusing|none] [--iterations <n>] [--snapshot-every <n>] " +
                "[--radial-stride <n>] [--axial-stride <n>]");
            return 2;
        }

        var input = new File(options.get("input"));
        var output = new File(options.get("output"));
        var data = BlochDataReader.read(input);
        var scenarioName = options.get("scenario");
        var outputScenario = options.getOrDefault("output-scenario", scenarioName + " Java");
        var objectiveMode = "periodic".equalsIgnoreCase(options.get("objective"))
            ? ObjectiveMode.PERIODIC_CYCLE
            : ObjectiveMode.FULL_TRAIN;
        var maskMode = switch (options.getOrDefault("mask-mode", "all").toLowerCase()) {
            case "none" -> FreeMaskMode.NONE;
            case "refocusing", "refocusing-only" -> FreeMaskMode.REFOCUSING_ONLY;
            default -> FreeMaskMode.ALL;
        };
        int prefixSegments = Integer.parseInt(options.getOrDefault("prefix-segments", "0"));
        int iterations = Integer.parseInt(options.getOrDefault("iterations", "60"));
        int snapshotEvery = Integer.parseInt(options.getOrDefault("snapshot-every", "10"));
        int radialStride = Integer.parseInt(options.getOrDefault("radial-stride", "1"));
        int axialStride = Integer.parseInt(options.getOrDefault("axial-stride", "1"));

        var builder = new ScenarioBuilder();
        var request = builder.buildRequest(data, new ScenarioBuilder.BuildOptions(
            scenarioName,
            options.get("iteration"),
            outputScenario,
            objectiveMode,
            prefixSegments,
            maskMode,
            iterations,
            snapshotEvery,
            radialStride,
            axialStride
        ));

        out.printf("Optimising scenario '%s' -> '%s'%n", request.seedScenarioName(), request.outputScenarioName());
        out.printf("Mode=%s, segments=%d, snapshots every %d iterations%n",
            request.problem().objectiveSpec().mode(),
            request.problem().sequenceTemplate().reducedSegmentCount(),
            request.snapshotEvery()
        );

        var result = new MaskedParameterOptimiser().optimise(request, new CpuObjectiveEngine());
        new SnapshotExporter().write(output, data, request, result);

        out.printf("Completed: iterations=%d, evaluations=%d, bestValue=%.6f, success=%s%n",
            result.iterations(),
            result.evaluations(),
            result.bestValue(),
            result.success()
        );
        out.printf("Wrote %s%n", output.getAbsolutePath());
        return 0;
    }

    private static Map<String, String> parseArgs(String[] args) {
        var options = new HashMap<String, String>();
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if (!arg.startsWith("--")) continue;
            String key = arg.substring(2);
            String value = index + 1 < args.length && !args[index + 1].startsWith("--")
                ? args[++index]
                : "true";
            options.put(key, value);
        }
        return options;
    }
}
