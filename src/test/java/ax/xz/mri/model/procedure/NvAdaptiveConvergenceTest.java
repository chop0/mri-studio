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
 * Convergence smoke-test for the {@code nv-adaptive-coherent} starter.
 *
 * <p>Builds the NV-centre-diamond template, runs the full adaptive loop via
 * the {@link ScriptHarness}, and asserts the dense-grid RMSE at the end is
 * at least {@link #CONVERGENCE_RATIO}× smaller than the initial GP-prior
 * RMSE. Also dumps the truth peak, posterior peak, and convergence-history
 * snapshot so a regression here will surface a legible diagnostic, not an
 * unhelpful "rmse went up".
 */
final class NvAdaptiveConvergenceTest {

    /**
     * Convergence quality bars.
     *
     * <p>{@link #SHAPE_TOLERANCE} — the posterior peak |B| must land within
     * this fraction of the truth peak |B|. With τ=100 µs, SHOTS=50 000, and
     * 2500 iters, σ_M ≈ 0.43 per Ramsey block; the GP prior + I-optimal
     * action schedule give ≈ 25 % under-shoot on truth peaks (the prior
     * over-regularises slightly at this SNR). Tolerance 0.4 catches genuine
     * shape regressions without flagging tiny shifts in the rng-driven
     * residual.
     *
     * <p>{@link #CONVERGENCE_RATIO} — rmseStart / rmseEnd must be ≥ this.
     * Setting it above 1.0 enforces that the EKF <em>actually converges</em>
     * (end rmse lower than start), not merely "doesn't diverge". A ratio of
     * 1.3 corresponds to ≈ 23 % rmse reduction from the GP-prior baseline,
     * which the procedure beats comfortably (×1.5 typical).
     */
    private static final double SHAPE_TOLERANCE = 0.4;
    private static final double CONVERGENCE_RATIO = 1.3;

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
    }

    private static int countChannels(List<CircuitComponent> components) {
        int n = 0;
        for (var c : components) {
            if (c instanceof CircuitComponent.VoltageSource src) n += src.kind().channelCount();
        }
        return n;
    }
}
