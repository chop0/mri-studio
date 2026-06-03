package ax.xz.mri.model.procedure;

import ax.xz.mri.dsl.ProcedureEngine;
import ax.xz.mri.dsl.Script;
import ax.xz.mri.dsl.ScriptResult;
import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.SimulationCompiler;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.service.procedure.ScriptHarness;
import ax.xz.mri.service.procedure.SimulatorObservationSource;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.ui.wizard.starters.SimConfigTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Convergence + parity test for the {@code nv-adaptive-coherent} starter.
 *
 * <p>The NV-diamond template is a faithful port of the reference
 * {@code adaptive_gradient_1d.ipynb} notebook: the same 32-NV layout
 * ({@code numpy.default_rng(0)}), the same Lorentzian-difference {@code B_true},
 * the same GP prior, the two-phase-τ ladder, and a shot count chosen so the
 * simulator's σ_M matches the notebook's. Running the full adaptive loop
 * through the {@link ScriptHarness} therefore reproduces the notebook's result:
 * dense RMSE ≈ 11 nT (the notebook's 6-seed spread is 7–17 nT, mean 11.4), with
 * the posterior peak ≈13 % under the truth peak (the GP prior over-regularises
 * at this SNR — the notebook shows the same undershoot).
 */
final class NvAdaptiveConvergenceTest {

    /**
     * Convergence + parity bars.
     *
     * <p>{@link #CONVERGENCE_RATIO} — rmseStart / rmseEnd must be ≥ this. The
     * two-phase-τ schedule achieves ×3.4 here; 2.5 enforces real convergence
     * with headroom. (The old fixed-τ gradient-ceiling warmup managed ×2.0.)
     *
     * <p>{@link #SHAPE_TOLERANCE} — posterior peak within this fraction of the
     * truth peak (≈13 % here; 0.2 leaves margin for the inherent low-SNR
     * undershoot without flagging a genuine shape regression).
     *
     * <p>{@link #MAX_RMSE_T} — the dense RMSE must land inside the notebook's
     * spread (its worst of 6 seeds was 16.9 nT), proving the Java simulator
     * reproduces the reference, not just "converges".
     */
    private static final double SHAPE_TOLERANCE = 0.2;
    private static final double CONVERGENCE_RATIO = 2.5;
    private static final double MAX_RMSE_T = 18e-9;

    @Test
    void adaptiveCoherentConvergesAgainstNvDiamondTemplate() throws Exception {
        var built = SimConfigTemplate.NV_CENTRE_DIAMOND.buildCircuit(ProjectState.empty(), "NV conv test");
        var repo = ProjectState.empty();
        for (var ef : built.newEigenfields()) repo = repo.withEigenfield(ef);
        for (var sub : built.newSubstances()) repo = repo.withSubstance(sub);
        repo = repo.withCircuit(built.circuit());

        var cfg = new SimulationConfig(
            SimConfigTemplate.NV_CENTRE_DIAMOND.referenceB0Tesla(),
            SimConfigTemplate.NV_CENTRE_DIAMOND.defaultPhysics().dtSeconds(),
            built.circuit().id());

        int channels = countChannels(built.circuit().components());
        var step = new PulseStep(new double[Math.max(channels, 1)], 0.0);
        var pulse = List.of(new PulseSegment(List.of(step)));
        var segments = List.of(new Segment(1.0e-6, 0, 1));
        var sim = new SimulationCompiler().compile(cfg, segments, pulse, repo);

        var procSource = ProcedureStarterLibrary.byId("nv-adaptive-coherent").orElseThrow().source();
        Script script = ProcedureEngine.compile(procSource);
        var obsSource = new SimulatorObservationSource(cfg, repo);

        ScriptResult result;
        try (var harness = new ScriptHarness()) {
            var future = harness.run(script, sim, obsSource, 0xC0FFEEL, tick -> { /* ignore in convergence test */ });
            result = future.get();
        }

        double[] rmseHist  = (double[]) result.outputs().get("rmseHist");
        double[] truthEval = (double[]) result.outputs().get("truthAtEval");
        double[] postB     = (double[]) result.outputs().get("posteriorB");

        double truthPeak = 0, postPeak = 0;
        for (double v : truthEval) truthPeak = Math.max(truthPeak, Math.abs(v));
        for (double v : postB)     postPeak  = Math.max(postPeak, Math.abs(v));

        double rmseStart = rmseHist[0];
        double rmseEnd   = rmseHist[rmseHist.length - 1];
        double ratio     = rmseStart / Math.max(rmseEnd, 1e-30);

        System.out.printf("[NV adaptive] truth peak = %.3e T, posterior peak = %.3e T%n",
            truthPeak, postPeak);
        System.out.printf("[NV adaptive] rmse: iter 0 = %.3e T, iter %d = %.3e T (×%.1f better)%n",
            rmseStart, rmseHist.length - 1, rmseEnd, ratio);
        System.out.println("[NV adaptive] summary: " + result.summary());

        assertTrue(truthPeak > 1e-10,
            "truth field is essentially zero at NV positions — circuit / eigenfield misconfigured");
        // Actual convergence: end rmse must be measurably lower than start.
        // Catches sign errors, runaway EKF, AND noise-limited stagnation.
        assertTrue(ratio >= CONVERGENCE_RATIO,
            "rmse must converge by at least " + CONVERGENCE_RATIO + "× (start=" + rmseStart
            + ", end=" + rmseEnd + ", ratio=" + ratio + "×)");
        double shapeError = Math.abs(postPeak - truthPeak) / truthPeak;
        assertTrue(shapeError <= SHAPE_TOLERANCE,
            "posterior peak must match truth peak within " + SHAPE_TOLERANCE
            + " (truth=" + truthPeak + ", posterior=" + postPeak + ", error=" + shapeError + ")");
        // Parity with the reference notebook: the dense RMSE must land inside
        // its 7–17 nT spread, proving the Java sim reproduces the experiment.
        assertTrue(rmseEnd < MAX_RMSE_T,
            "dense RMSE must match the notebook's distribution (< " + MAX_RMSE_T + " T, got " + rmseEnd + ")");
    }

    private static int countChannels(List<CircuitComponent> components) {
        int n = 0;
        for (var c : components) {
            if (c instanceof CircuitComponent.VoltageSource src) n += src.kind().channelCount();
        }
        return n;
    }
}
