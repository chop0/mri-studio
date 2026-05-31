package ax.xz.mri;

import ax.xz.mri.dsl.Script;
import ax.xz.mri.dsl.ScriptResult;
import ax.xz.mri.dsl.viz.Visualisation;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.SimulationCompiler;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.service.procedure.ScriptHarness;
import ax.xz.mri.service.procedure.SimulatorObservationSource;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.state.ProjectStateIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Public entry point for running an mri script from a standalone Java
 * file — the bridge between a user's {@code script.java} file and the
 * studio's harness, project loader, and visualisation stack.
 *
 * <p>Typical use from a single-file script:
 * <pre>{@code
 *   import module ax.xz.mri;
 *
 *   class MyScript implements Script {
 *       public void run(ScriptContext ctx) throws InterruptedException {
 *           // … script body …
 *       }
 *       void main() {
 *           NMRStudio.runScript(new MyScript());
 *       }
 *   }
 * }</pre>
 *
 * <p>The script is launched via {@code java}'s single-file source-code
 * launcher (JEP 330) against the jlink-built studio image, which has
 * {@code ax.xz.mri} resolved on the boot module-path — so
 * {@code import module ax.xz.mri;} resolves with no extra flags.
 *
 * <p>The runner auto-discovers the surrounding mri-project by walking up
 * from the current working directory (then the calling class's code-source
 * location, then the JDK single-file launcher's source-file path) looking
 * for {@code mri-project.toml}. It loads the project state, picks the
 * simulation config named by {@link RunOptions#simulationName()} (defaulting
 * to {@code mri-project.toml}'s {@code active_simulation} key, then to the
 * sole sim config if there's exactly one), compiles it, builds a
 * {@link SimulatorObservationSource}, threads the lot through a
 * {@link ScriptHarness}, and blocks until the script completes.
 */
public final class NMRStudio {
    private NMRStudio() {}

    /** Filename of the project manifest at the root of every mri-project. */
    public static final String PROJECT_MANIFEST_FILENAME = "mri-project.toml";

    /** {@link #runScript(Script, RunOptions)} with {@link RunOptions#defaults()}. */
    public static ScriptResult runScript(Script script) {
        return runScript(script, RunOptions.defaults());
    }

    /**
     * Discover the surrounding mri-project, run {@code script} against its
     * active simulation, and block until it completes. The returned
     * {@link ScriptResult} carries whatever the script stashed via
     * {@code ctx.put(...)} plus the summary it set with {@code ctx.summary(...)}.
     */
    public static ScriptResult runScript(Script script, RunOptions options) {
        if (script == null) throw new IllegalArgumentException("script must not be null");
        if (options == null) throw new IllegalArgumentException("options must not be null");

        // Caller-class lookup needs to happen here, before we recurse into
        // helpers, so the StackWalker can see the user's main() frame
        // immediately above this method's frame.
        Class<?> callerClass = callerClass();

        var projectRoot = resolveProjectRoot(options, callerClass);
        var state = loadProjectState(projectRoot);
        var sim = pickSimulation(state, options.simulationName().orElse(null));

        return run(script, options, projectRoot, state, sim);
    }

    /* ── Implementation ─────────────────────────────────────────────────── */

    private static ScriptResult run(Script script, RunOptions options,
                                    Path projectRoot, ProjectState state,
                                    SimulationConfigDocument sim) {
        var cfg = sim.config();

        // The standalone simulation only needs to hold a "default field"
        // sample for ctx.simulation() reads (staticBzAt, substances, …);
        // every actual experiment goes through observationSource.run() which
        // compiles its own per-action sim. A one-step zero pulse is enough
        // to bring CompiledSimulation up.
        int channels = state.circuit(cfg.circuitId()) == null ? 1
            : Math.max(1, state.circuit(cfg.circuitId()).totalChannelCount());
        var initialPulse = List.of(new PulseSegment(List.of(new PulseStep(new double[channels], 0.0))));
        var initialSegments = List.of(new Segment(1.0e-6, 1, 0));
        var compiledSim = new SimulationCompiler().compile(cfg, initialSegments, initialPulse, state);
        var obsSource = new SimulatorObservationSource(cfg, state);

        long seed = options.seed().orElseGet(System::nanoTime);
        StandaloneProcedureWindow window = options.showCharts() ? StandaloneProcedureWindow.open() : null;
        Consumer<ScriptHarness.Tick> tickConsumer = window != null ? window : stdoutTickConsumer();
        printRunBanner(script, projectRoot, sim, seed, window != null);

        try (var harness = new ScriptHarness()) {
            var future = harness.run(script, compiledSim, obsSource, seed, tickConsumer);
            ScriptResult result;
            try {
                result = future.join();
            } catch (CompletionException ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof RuntimeException re) throw re;
                throw new RuntimeException(cause);
            }
            System.out.println();
            System.out.println("[mri] script finished: " + result.summary());
            if (window != null) window.awaitClose();
            return result;
        }
    }

    private static void printRunBanner(Script script, Path projectRoot,
                                       SimulationConfigDocument sim, long seed, boolean charts) {
        System.out.println("[mri] project   : " + projectRoot);
        System.out.println("[mri] simulation: " + sim.name() + "  (" + sim.id().value() + ")");
        System.out.println("[mri] script    : " + script.getClass().getSimpleName());
        System.out.println("[mri] seed      : 0x" + Long.toHexString(seed));
        System.out.println("[mri] charts    : " + (charts ? "JavaFX window" : "stdout summaries"));
        System.out.println();
    }

    /**
     * Stdout consumer used by {@code NMRStudio.runScript} when the user
     * opts out of the JavaFX chart window. Coalesces a script's
     * {@code status} / {@code progress} stream into a single rewritten
     * line (carriage return, no newline) so the user sees one "what's
     * happening now" indicator that updates in place; log lines and
     * visualisation summaries flush newlines, since they're append-only.
     */
    private static Consumer<ScriptHarness.Tick> stdoutTickConsumer() {
        var lastStatus = new String[]{null};
        var lastProgress = new Double[]{null};
        return tick -> {
            // Pump a status/progress update line whenever either changes.
            boolean statusChanged = tick.status() != null && !tick.status().equals(lastStatus[0]);
            boolean progressChanged = tick.progress() != null && !tick.progress().equals(lastProgress[0]);
            if (statusChanged) lastStatus[0] = tick.status();
            if (progressChanged) lastProgress[0] = tick.progress();
            if (statusChanged || progressChanged) {
                String statusText = lastStatus[0] == null ? "" : lastStatus[0];
                String progressText;
                if (lastProgress[0] == null) progressText = "";
                else if (Double.isNaN(lastProgress[0])) progressText = "  (…)";
                else progressText = String.format("  [%3d%%]", (int) Math.round(lastProgress[0] * 100));
                System.out.print("\r[mri] " + statusText + progressText + "          ");
                System.out.flush();
            }
            if (tick.log() != null && !tick.log().isBlank()) {
                System.out.println();
                System.out.println("[mri] " + tick.log());
            }
            for (var m : tick.metrics().entrySet()) {
                System.out.println();
                System.out.printf("            %-12s = %.6g%n", m.getKey(), m.getValue());
            }
            for (var v : tick.visualisations()) {
                System.out.println();
                System.out.println("            " + summariseVisualisation(v));
            }
        };
    }

    private static String summariseVisualisation(Visualisation viz) {
        return switch (viz) {
            case Visualisation.Line line ->
                "[show] " + line.id() + ": Line — " + line.series().size() + " series, "
                    + line.series().stream()
                        .map(s -> s.label() + " (" + s.x().length + " pts)")
                        .reduce((a, b) -> a + ", " + b).orElse("");
            case Visualisation.Heatmap heat ->
                "[show] " + heat.id() + ": Heatmap — " + heat.data().length + "×"
                    + (heat.data().length == 0 ? 0 : heat.data()[0].length);
            case Visualisation.Histogram hist ->
                "[show] " + hist.id() + ": Histogram — " + hist.values().length + " values, "
                    + hist.bins() + " bins";
            case Visualisation.Bars bars ->
                "[show] " + bars.id() + ": Bars — " + bars.categories().length + " bars";
            case Visualisation.Scalar scalar ->
                String.format("[show] %s: Scalar — %.6g %s",
                    scalar.id(), scalar.value(), scalar.unit() == null ? "" : scalar.unit());
        };
    }

    /* ── Project discovery ──────────────────────────────────────────────── */

    /**
     * Resolve the active project root. Order:
     * <ol>
     *   <li>{@link RunOptions#projectRoot()} if explicitly set.</li>
     *   <li>The {@code -Dmri.project=...} system property.</li>
     *   <li>Walk up from {@code System.getProperty("user.dir")} until the
     *       manifest is found.</li>
     *   <li>Walk up from {@code callerClass.getProtectionDomain()
     *       .getCodeSource().getLocation()} — useful when the caller is
     *       launched from inside a {@code build/classes/} dir whose nearest
     *       containing project root sits a few levels above.</li>
     *   <li>Walk up from {@code System.getProperty("jdk.launcher.source.file")}
     *       — the JDK single-file launcher sets this to the original .java
     *       path of a script run as {@code java MyScript.java}.</li>
     * </ol>
     * Throws with the list of attempted paths if none yields a manifest.
     */
    static Path resolveProjectRoot(RunOptions options, Class<?> callerClass) {
        if (options.projectRoot().isPresent()) {
            return verifyProject(options.projectRoot().get(), "RunOptions.projectRoot");
        }
        var attempts = new LinkedHashMap<String, Path>();

        String sysprop = System.getProperty("mri.project");
        if (sysprop != null) attempts.put("-Dmri.project", Path.of(sysprop));

        attempts.put("cwd", Path.of(System.getProperty("user.dir", ".")).toAbsolutePath());

        Path codeSource = codeSourcePath(callerClass);
        if (codeSource != null) attempts.put("caller code source", codeSource);

        String singleFile = System.getProperty("jdk.launcher.source.file");
        if (singleFile != null) {
            attempts.put("jdk.launcher.source.file", Path.of(singleFile).toAbsolutePath());
        }

        for (var attempt : attempts.entrySet()) {
            Path hit = walkUpForManifest(attempt.getValue());
            if (hit != null) return hit;
        }

        var msg = new StringBuilder("No mri-project.toml found. Checked:\n");
        attempts.forEach((label, path) -> msg.append("  - ").append(label).append(": ").append(path).append('\n'));
        msg.append("Set -Dmri.project=<path> or call NMRStudio.runScript(s, RunOptions.defaults().withProject(...)).");
        throw new IllegalStateException(msg.toString());
    }

    private static Path walkUpForManifest(Path start) {
        Path p = start;
        if (p == null) return null;
        if (!Files.isDirectory(p)) p = p.getParent();
        while (p != null) {
            if (Files.isRegularFile(p.resolve(PROJECT_MANIFEST_FILENAME))) return p;
            p = p.getParent();
        }
        return null;
    }

    private static Path verifyProject(Path root, String label) {
        if (!Files.isRegularFile(root.resolve(PROJECT_MANIFEST_FILENAME))) {
            throw new IllegalStateException(label + " does not contain " + PROJECT_MANIFEST_FILENAME + ": " + root);
        }
        return root;
    }

    private static Class<?> callerClass() {
        var walker = StackWalker.getInstance(Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE));
        return walker.walk(frames -> frames
            .filter(f -> !f.getDeclaringClass().getName().equals(NMRStudio.class.getName()))
            .map(StackWalker.StackFrame::getDeclaringClass)
            .findFirst().orElse(null));
    }

    private static Path codeSourcePath(Class<?> cls) {
        if (cls == null) return null;
        try {
            var src = cls.getProtectionDomain().getCodeSource();
            if (src == null) return null;
            var loc = src.getLocation();
            if (loc == null) return null;
            return Path.of(loc.toURI());
        } catch (Exception e) {
            return null;
        }
    }

    /* ── Sim picking + project load ────────────────────────────────────── */

    private static ProjectState loadProjectState(Path projectRoot) {
        try {
            return new ProjectStateIO().read(projectRoot);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to load project at " + projectRoot + ": " + ex.getMessage(), ex);
        }
    }

    private static SimulationConfigDocument pickSimulation(ProjectState state, String requested) {
        var simIds = state.simulationIds();
        if (simIds.isEmpty()) {
            throw new IllegalStateException("project has no simulation configs — add at least one in the studio first");
        }
        if (requested == null) requested = state.manifest().activeSimulation();
        if (requested != null) {
            for (var id : simIds) {
                var doc = state.simulation(id);
                if (doc != null && requested.equals(doc.name())) return doc;
                if (doc != null && requested.equals(id.value())) return doc;
            }
            throw new IllegalStateException("simulation '" + requested + "' not found. Available: "
                + simIds.stream().map(ProjectNodeId::value).toList());
        }
        if (simIds.size() == 1) return state.simulation(simIds.iterator().next());
        throw new IllegalStateException("project has " + simIds.size() + " simulation configs; specify which one to use via "
            + "active_simulation in mri-project.toml or RunOptions.withSim(name). "
            + "Available: " + simIds.stream().map(id -> state.simulation(id).name()).toList());
    }

    /* ── Options ─────────────────────────────────────────────────────────── */

    /**
     * Knobs the standalone runner honours. Use the no-arg
     * {@link #defaults()} factory and chain {@code with…} methods to
     * customise:
     * <pre>{@code
     *   NMRStudio.runScript(new MyScript(),
     *       RunOptions.defaults().withSim("low-field-mri").headless());
     * }</pre>
     */
    public record RunOptions(
        Optional<Path> projectRoot,
        Optional<String> simulationName,
        OptionalLong seed,
        boolean showCharts
    ) {
        public static RunOptions defaults() {
            return new RunOptions(Optional.empty(), Optional.empty(), OptionalLong.empty(), true);
        }
        public RunOptions withProject(Path p) {
            return new RunOptions(Optional.ofNullable(p), simulationName, seed, showCharts);
        }
        public RunOptions withSim(String name) {
            return new RunOptions(projectRoot, Optional.ofNullable(name), seed, showCharts);
        }
        public RunOptions withSeed(long s) {
            return new RunOptions(projectRoot, simulationName, OptionalLong.of(s), showCharts);
        }
        public RunOptions headless() {
            return new RunOptions(projectRoot, simulationName, seed, false);
        }
    }

}
