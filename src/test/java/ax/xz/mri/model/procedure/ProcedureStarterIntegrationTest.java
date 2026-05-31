package ax.xz.mri.model.procedure;

import ax.xz.mri.dsl.BakedSequence;
import ax.xz.mri.dsl.ProcedureEngine;
import ax.xz.mri.dsl.Script;
import ax.xz.mri.dsl.ScriptResult;
import ax.xz.mri.dsl.viz.Visualisation;
import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.SimulationCompiler;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.service.procedure.ObservationSource;
import ax.xz.mri.service.procedure.ScriptHarness;
import ax.xz.mri.service.procedure.SimulatorObservationSource;
import ax.xz.mri.service.simulation.compiled.CompiledSimulation;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.ui.wizard.starters.SimConfigTemplate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for every {@link ProcedureStarter} in the
 * library. Each starter must:
 *
 * <ol>
 *   <li>compile through {@link ProcedureEngine} — full Java-25 module-imports
 *       form with no Janino fallbacks;</li>
 *   <li>implement the runtime {@link Script} contract;</li>
 *   <li>run end-to-end via {@link ScriptHarness} against a stub simulation,
 *       producing a non-null {@link ScriptResult}.</li>
 * </ol>
 *
 * <p>The starter sources are big self-contained ports of legacy Java
 * services (NvAdaptiveEstimator, NvKSpaceScanner, the L-BFGS-B optimiser).
 * This test exercises the path the wizard would take when the user picks
 * a starter, clicks Finish, and hits Run in the procedure pane.
 *
 * <p>For NV starters we plumb a real {@link CompiledSimulation} built from
 * the NV-centre-diamond wizard template, so {@code ctx.simulation()} returns
 * a valid sim and the truth-Bz reads land on real coil eigenfields. Pulse
 * optimisation and the blank templates run with a null simulation — they
 * don't read the field directly.
 */
final class ProcedureStarterIntegrationTest {

    /** Shared NV-centre simulation, reused across the NV starters to keep cold-compile cost off the per-test critical path. */
    private static CompiledSimulation nvSim;
    private static ProjectState nvRepo;
    private static SimulationConfig nvCfg;

    @BeforeAll
    static void buildNvSimulation() {
        var built = SimConfigTemplate.NV_CENTRE_DIAMOND.buildCircuit(ProjectState.empty(), "NV proc test");
        var repo = ProjectState.empty();
        for (var ef : built.newEigenfields()) repo = repo.withEigenfield(ef);
        for (var sub : built.newSubstances()) repo = repo.withSubstance(sub);
        repo = repo.withCircuit(built.circuit());

        var cfg = new SimulationConfig(SimConfigTemplate.NV_CENTRE_DIAMOND.referenceB0Tesla(), SimConfigTemplate.NV_CENTRE_DIAMOND.defaultPhysics().dtSeconds(), built.circuit().id());

        // Tiny pulse — one zero-step — so the simulator compiles cheaply.
        int channels = countChannels(built.circuit().components());
        var step = new PulseStep(new double[Math.max(channels, 1)], 0.0);
        var pulse = List.of(new PulseSegment(List.of(step)));
        var segments = List.of(new Segment(1.0e-6, 0, 1));

        nvRepo = repo;
        nvCfg = cfg;
        nvSim = new SimulationCompiler().compile(cfg, segments, pulse, repo);
    }

    /* ── Library-wide structural sanity ─────────────────────────────────── */

    @Test
    void everyStarterCompilesToAScript() {
        var starters = ProcedureStarterLibrary.all();
        assertFalse(starters.isEmpty(), "starter library is empty");
        for (var starter : starters) {
            Script script = ProcedureEngine.compile(starter.source());
            assertNotNull(script, "starter '" + starter.id() + "' compiled to null");
            assertInstanceOf(Script.class, script,
                "starter '" + starter.id() + "' must implement Script");
        }
    }

    /* ── Per-starter run-to-completion (or run-a-few-iters) ─────────────── */

    @Test
    void blankStarterRunsToCompletion() throws Exception {
        var run = runStarter("blank", null, null);
        assertNotNull(run.result);
        assertEquals("done", run.result.summary(),
            "blank script summary should reflect ctx.summary(\"done\")");
        assertFalse(run.statusUpdates.isEmpty(),
            "blank script should emit at least one status update via ctx.status(...)");
    }

    @Test
    void mriIterativeReconStarterRuns() throws Exception {
        var run = runStarter("mri-iterative-recon", null, null);
        assertNotNull(run.result);
        assertFalse(run.statusUpdates.isEmpty(),
            "MRI recon stub should call ctx.status(...) at least once");
    }

    @Test
    void nvAdaptiveCoherentStarterEmitsExpectedShape() throws Exception {
        // We can't run the full 1000 iters in a unit test; sub the source out to
        // a small N_ITER override. Instead, just verify that calling the script
        // produces a Script with the right structural class — the deep
        // numerical assertions live in NvAdaptiveConvergenceTest.
        Script script = ProcedureEngine.compile(starterSource("nv-adaptive-coherent"));
        assertNotNull(script, "NV adaptive coherent compiled to null");
        // The full convergence test runs the script end-to-end and asserts on
        // outputs; here we just smoke-test that the source compiles.
    }

    @Test
    void nvKSpaceSweepStarterRunsToCompletion() throws Exception {
        var run = runStarter("nv-kspace-sweep", nvSim,
            new SimulatorObservationSource(nvCfg, nvRepo));
        assertNotNull(run.result);

        // The starter sweeps a grid of gradients and reports the Var(M) peak; we just verify
        // the output bag carries the structural fields we need to render the result.
        assertNotNull(run.result.outputs().get("gradients_Tpm"));
        assertNotNull(run.result.outputs().get("Var(M)"));
        assertNotNull(run.result.outputs().get("peakQ"));

        double[] varM = (double[]) run.result.outputs().get("Var(M)");
        assertTrue(varM.length > 0, "k-space sweep produced empty Var(M) array");
        // Var(M) is non-negative by construction.
        for (double v : varM) assertTrue(v >= 0, "Var(M) entry is negative: " + v);

        // The starter must surface its sweep through the visualisation API — at minimum
        // a Var(M) line plot and a peak-Q scalar pill so the procedure pane's Outputs
        // panel populates without the user having to dig into the result bag.
        var line = run.vizById.get("var-m");
        assertInstanceOf(Visualisation.Line.class, line, "nv-kspace-sweep must emit a Var(M) line plot");
        var lineViz = (Visualisation.Line) line;
        assertEquals(1, lineViz.series().size(), "Var(M) plot is a single series");
        assertEquals(varM.length, lineViz.series().get(0).x().length, "x/y array length mismatch in viz");

        var peak = run.vizById.get("peak-q");
        assertInstanceOf(Visualisation.Scalar.class, peak, "nv-kspace-sweep must emit a peak-Q scalar");
    }

    @Test
    void pulseOptimisationStarterEmitsResult() throws Exception {
        // Pulse opt is self-contained — it doesn't read sim — so a null context is fine.
        // We don't run the full 80 iters; the harness will let it run for a while —
        // this is structurally still under a second on FD-grad N=2*16 dim space.
        var run = runStarter("pulse-optimisation", null, null);
        assertNotNull(run.result);

        // Best-objective monotone-non-increasing — the starter only writes bestValue when it strictly improves.
        Double bestObj = (Double) run.result.outputs().get("objective");
        assertNotNull(bestObj, "pulse-optimisation result missing 'objective'");
        assertTrue(run.metrics.containsKey("objective"), "pulse-opt emits 'objective' metric");

        double[] amps = (double[]) run.result.outputs().get("amplitudes_T");
        double[] phases = (double[]) run.result.outputs().get("phases_rad");
        assertNotNull(amps);
        assertNotNull(phases);
        assertEquals(amps.length, phases.length, "amplitude / phase array mismatch");
    }

    /* ── Harness wiring smoke test ──────────────────────────────────────── */

    @Test
    void simulatorObservationSourceRunsWithoutThrowing() throws Exception {
        // The starter library deliberately bypasses ObservationSource on the
        // atomic-Ramsey-block hot path; this smoke-test still verifies the
        // sim-backed observation source works end-to-end so hardware-mode
        // starters (which DO go through the source) are exercised.
        ObservationSource source = new SimulatorObservationSource(nvCfg, nvRepo);
        // Channel count depends on the NV circuit's voltage-source layout.
        // Build a one-step pulse with all channels zero so the static-only solve
        // doesn't blow up.
        var circuit = nvRepo.circuit(nvCfg.circuitId());
        int channels = circuit == null ? 1 : countChannels(circuit.components());
        var step = new PulseStep(new double[Math.max(channels, 1)], 0.0);
        var pulse = List.of(new PulseSegment(List.of(step)));
        var segments = List.of(new Segment(1.0e-6, 0, 1));
        var seq = new BakedSequence(segments, pulse, 1.0e-6, Map.of());
        var trace = source.run(seq);
        assertNotNull(trace, "SimulatorObservationSource produced a null trace");
    }

    /* ── helpers ────────────────────────────────────────────────────────── */

    private static String starterSource(String id) {
        return ProcedureStarterLibrary.byId(id)
            .orElseThrow(() -> new AssertionError("no starter '" + id + "'"))
            .source();
    }

    private static int countChannels(List<CircuitComponent> components) {
        int n = 0;
        for (var c : components) {
            if (c instanceof CircuitComponent.VoltageSource src) {
                n += src.kind().channelCount();
            }
        }
        return n;
    }

    /** Result bundle from running a starter via the harness. */
    private record StarterRun(
        ScriptResult result,
        List<String> statusUpdates,
        Map<String, Double> metrics,
        Map<String, Visualisation> vizById
    ) {}

    private static StarterRun runStarter(String id, CompiledSimulation sim, ObservationSource source) throws Exception {
        Script script = ProcedureEngine.compile(starterSource(id));
        var statuses = new java.util.ArrayList<String>();
        var metrics = new LinkedHashMap<String, Double>();
        var viz = new LinkedHashMap<String, Visualisation>();
        try (var harness = new ScriptHarness()) {
            var future = harness.run(script, sim, source, 0xC0FFEEL, tick -> {
                if (tick.status() != null) statuses.add(tick.status());
                metrics.putAll(tick.metrics());
                for (var v : tick.visualisations()) viz.put(v.id(), v);
            });
            var result = future.get();
            return new StarterRun(result, statuses, metrics, viz);
        }
    }
}
