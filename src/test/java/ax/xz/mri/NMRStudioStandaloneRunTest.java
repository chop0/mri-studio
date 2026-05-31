package ax.xz.mri;

import ax.xz.mri.dsl.ProcedureEngine;
import ax.xz.mri.dsl.Script;
import ax.xz.mri.dsl.ScriptContext;
import ax.xz.mri.model.procedure.ProcedureStarterLibrary;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.ProjectManifest;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.state.ProjectStateIO;
import ax.xz.mri.ui.wizard.starters.SimConfigTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for the standalone runner: builds an on-disk project,
 * calls {@link NMRStudio#runScript}, and asserts the script actually runs
 * against the project's simulation through the full discovery + loading +
 * harness pipeline.
 *
 * <p>Run headless (no chart window) so the test suite stays UI-free.
 */
final class NMRStudioStandaloneRunTest {

    @TempDir
    Path projectRoot;

    @Test
    void runsNvAdaptiveCoherentAgainstWrittenProject() throws Exception {
        writeNvProject(projectRoot, "NV test", "nv-sim");

        // Compile the NV-adaptive starter once so the test runs the real
        // production source — same code path the studio invokes.
        var script = ProcedureEngine.compile(ProcedureStarterLibrary
            .byId("nv-adaptive-coherent").orElseThrow().source());

        var result = NMRStudio.runScript(script,
            NMRStudio.RunOptions.defaults().withProject(projectRoot).headless().withSeed(0xC0FFEEL));

        assertNotNull(result, "runScript must return a result");
        assertNotNull(result.outputs().get("rmseHist"),
            "result should expose rmseHist (the convergence trace the script emits)");
        double[] rmse = (double[]) result.outputs().get("rmseHist");
        assertTrue(rmse.length > 0, "rmseHist should have at least one iteration sample");
        // Standalone runner converged with the same shape NvAdaptiveConvergenceTest
        // pins (we use the same seed) — sanity-checked end-to-end.
        assertTrue(rmse[rmse.length - 1] < rmse[0],
            "EKF should converge — end rmse < start rmse");
    }

    @Test
    void blankStarterRunsToCompletion() throws Exception {
        writeNvProject(projectRoot, "Blank script", "nv-sim");

        var script = ProcedureEngine.compile(ProcedureStarterLibrary
            .byId("blank").orElseThrow().source());
        var result = NMRStudio.runScript(script,
            NMRStudio.RunOptions.defaults().withProject(projectRoot).headless());
        assertNotNull(result.summary());
    }

    @Test
    void rejectsProjectDirWithoutManifest(@TempDir Path empty) {
        var ex = assertThrows(IllegalStateException.class, () ->
            NMRStudio.runScript(new NoopScript(),
                NMRStudio.RunOptions.defaults().withProject(empty).headless()));
        assertTrue(ex.getMessage().contains("mri-project.toml"),
            "error should mention the missing manifest, got: " + ex.getMessage());
    }

    @Test
    void rejectsProjectWithoutAnySimulation(@TempDir Path empty) throws Exception {
        var io = new ProjectStateIO();
        io.write(ProjectState.empty().withManifest(
            new ProjectManifest("Empty", ".mri-studio/layout.json", ".mri-studio/ui-state.json")),
            empty);
        var ex = assertThrows(IllegalStateException.class, () ->
            NMRStudio.runScript(new NoopScript(),
                NMRStudio.RunOptions.defaults().withProject(empty).headless()));
        assertTrue(ex.getMessage().contains("no simulation configs"),
            "error should mention missing simulation configs, got: " + ex.getMessage());
    }

    @Test
    void rejectsMultiSimProjectWithoutActiveSelection(@TempDir Path multi) throws Exception {
        var state = buildNvState("Multi-sim", "first")
            .withSimulation(simDoc("second", first(buildNvState("x", "second").simulationIds())));
        var io = new ProjectStateIO();
        io.write(state, multi);
        var ex = assertThrows(IllegalStateException.class, () ->
            NMRStudio.runScript(new NoopScript(),
                NMRStudio.RunOptions.defaults().withProject(multi).headless()));
        assertTrue(ex.getMessage().contains("specify which one"),
            "multi-sim error should ask the user to disambiguate, got: " + ex.getMessage());
    }

    /* ── helpers ─────────────────────────────────────────────────────────── */

    private static void writeNvProject(Path root, String name, String simName) throws Exception {
        var state = buildNvState(name, simName)
            .withManifest(new ProjectManifest(name, ".mri-studio/layout.json",
                ".mri-studio/ui-state.json", simName));
        new ProjectStateIO().write(state, root);
    }

    private static ProjectState buildNvState(String name, String simName) {
        var built = SimConfigTemplate.NV_CENTRE_DIAMOND.buildCircuit(ProjectState.empty(), name);
        var state = ProjectState.empty();
        for (var ef : built.newEigenfields()) state = state.withEigenfield(ef);
        for (var sub : built.newSubstances()) state = state.withSubstance(sub);
        state = state.withCircuit(built.circuit());
        var cfg = new SimulationConfig(
            SimConfigTemplate.NV_CENTRE_DIAMOND.referenceB0Tesla(),
            SimConfigTemplate.NV_CENTRE_DIAMOND.defaultPhysics().dtSeconds(),
            built.circuit().id());
        state = state.withSimulation(new SimulationConfigDocument(
            new ProjectNodeId("sim-" + UUID.randomUUID()), simName, cfg));
        return state;
    }

    private static SimulationConfigDocument simDoc(String name, ProjectNodeId sample) {
        // Just clone the existing simulation under a new id+name so we have
        // two sims to disambiguate between.
        return new SimulationConfigDocument(
            new ProjectNodeId("sim-" + UUID.randomUUID()), name,
            new SimulationConfig(0.01, 1.0e-9, sample));
    }

    private static <T> T first(java.util.Collection<T> c) { return c.iterator().next(); }

    private static final class NoopScript implements Script {
        @Override public void run(ScriptContext ctx) {
            ctx.summary("noop");
        }
    }
}
