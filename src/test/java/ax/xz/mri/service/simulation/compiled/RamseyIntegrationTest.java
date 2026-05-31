package ax.xz.mri.service.simulation.compiled;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.model.simulation.SimulationCompiler;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.ui.wizard.starters.SimConfigTemplate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end Ramsey: build the NV-centre-diamond circuit, drive a Ramsey
 * sequence (pump → π/2 → τ → π/2 → read), and verify the optical counter
 * trace shows:
 *
 * <ol>
 *   <li>Near-zero clicks while the laser is off.</li>
 *   <li>High clicks while the laser is on (pump and read windows).</li>
 *   <li>A Ramsey fringe in the read-window integral as τ is swept,
 *       under a deliberately-injected detuning.</li>
 * </ol>
 *
 * <p>The fringe period {@code T = 2π / (γ_NV · Δ)} sets τ-step. We pick
 * Δ ≈ 18 µT (B0 source set 18 µT above the rotating-frame reference) so
 * the fringe period is ~36 µs and τ ∈ {0, 8, 18, 28} µs spans multiple
 * cycles of cos(γΔτ).
 */
final class RamseyIntegrationTest {

    private static final double GAMMA_NV = 2 * Math.PI * 28.024e9;
    /** Detuning in tesla — adds to B0 source so each NV sees Bz = Δ in rotating frame. */
    private static final double DETUNING_T = 1.8e-5;       // 18 µT → fringe period ≈ 36 µs
    private static final double MW_AMP_T   = 8.9e-5;       // 89 µT envelope on MW I → π/2 in 100 ns
    private static final double T_PI_HALF_NS = 100;
    private static final double PUMP_NS = 3000;
    private static final double READ_NS = 500;             // short read window to see contrast before pumping out
    private static final double DT_NS   = 5;               // 5 ns dt — fine enough to integrate the MW pulse

    @Test
    void ramseyClicksTraceShowsLaserGating() {
        var built = SimConfigTemplate.NV_CENTRE_DIAMOND.buildCircuit(ProjectState.empty(), "Ramsey-gate");
        var circuit = withB0Bias(built.circuit(), 0.01);     // on-resonance: B0 = b0Ref
        var repo = installSatellites(built, circuit);
        var cfg = new SimulationConfig(SimConfigTemplate.NV_CENTRE_DIAMOND.referenceB0Tesla(), SimConfigTemplate.NV_CENTRE_DIAMOND.defaultPhysics().dtSeconds(), circuit.id());

        var seq = buildRamsey(circuit, 1_000.0 /* τ ns */);
        var sim = new SimulationCompiler().compile(cfg, seq.segments(), seq.pulse(), repo);
        assertNotNull(sim);
        assertTrue(sim.opticalCounters().size() >= 1,
            "NV starter must wire one OpticalCounter from clicks_red");

        var trace = sim.runMultiProbe();
        var red = trace.byProbe().get("Red counter");
        assertNotNull(red, "Red counter probe trace must be present in MultiProbeSignalTrace");
        var points = red.points();
        assertTrue(points.size() > 10, "trace should contain many sample points");

        // Pump window is [0, 3 µs]; dark gap is right after; read is at the tail.
        double pumpAvg = averageBetween(points, 0.5, 2.5);
        double darkAvg = averageBetween(points, 3.05, 3.10);
        assertTrue(pumpAvg > darkAvg * 10,
            "pump clicks should dwarf dark clicks (laser scale = 1.0 vs 1e-3). "
            + "pumpAvg=" + pumpAvg + " darkAvg=" + darkAvg);
    }

    @Test
    void ramseyFringeOverTauShowsContrast() {
        var built = SimConfigTemplate.NV_CENTRE_DIAMOND.buildCircuit(ProjectState.empty(), "Ramsey-fringe");
        // 18 µT detuning over τ ∈ {2, 4, 6, 8} µs spans ~63 % of a Ramsey cycle.
        double detuning = 1.8e-5;
        var circuit = withB0Bias(built.circuit(), 0.01 + detuning);
        var repo = installSatellites(built, circuit);
        var cfg = new SimulationConfig(SimConfigTemplate.NV_CENTRE_DIAMOND.referenceB0Tesla(), SimConfigTemplate.NV_CENTRE_DIAMOND.defaultPhysics().dtSeconds(), circuit.id());

        // Ramsey period at Δ = 18 µT is 2π/(γ_NV·Δ) ≈ 1.98 µs.
        // Sweep over half a period so cos(γ_NV·Δ·τ) walks through ±1 and 0.
        double[] tausUs = {0.5, 1.0, 1.5, 2.0};
        double[] readIntegrals = new double[tausUs.length];

        for (int k = 0; k < tausUs.length; k++) {
            double tauNs = tausUs[k] * 1000;
            var seq = buildRamsey(circuit, tauNs);
            var sim = new SimulationCompiler().compile(cfg, seq.segments(), seq.pulse(), repo);
            var trace = sim.runMultiProbe();
            var red = trace.byProbe().get("Red counter");
            assertNotNull(red, "Red counter trace must exist for τ=" + tauNs);

            // Read window starts after pump + dark + π/2 + τ + π/2 + dark.
            double readStartUs = (PUMP_NS + 10 + T_PI_HALF_NS + tauNs + T_PI_HALF_NS + 10) / 1000.0;
            // First 100 ns of read = max contrast.
            double readEndUs = readStartUs + 0.1;
            readIntegrals[k] = sumBetween(red.points(), readStartUs, readEndUs);
        }

        double min = readIntegrals[0], max = readIntegrals[0];
        for (double v : readIntegrals) { min = Math.min(min, v); max = Math.max(max, v); }
        double spread = max - min;
        double mean = (min + max) / 2.0;
        assertTrue(spread > Math.abs(mean) * 0.01 + 1e-12,
            "Read-integral should vary with τ. spread=" + spread + " mean=" + mean
            + " values=" + java.util.Arrays.toString(readIntegrals));
    }

    /* ── helpers ─────────────────────────────────────────────────────────── */

    private static ProjectState installSatellites(ax.xz.mri.model.circuit.starter.CircuitStarter.Result built,
                                                  CircuitDocument circuitOverride) {
        var repo = ProjectState.empty();
        for (var ef : built.newEigenfields()) repo = repo.withEigenfield(ef);
        for (var sub : built.newSubstances()) repo = repo.withSubstance(sub);
        return repo.withCircuit(circuitOverride);
    }

    /** Replace the B0 source's static amplitude (= maxAmplitude for STATIC kind). */
    private static CircuitDocument withB0Bias(CircuitDocument circuit, double biasT) {
        var newComponents = new ArrayList<CircuitComponent>(circuit.components());
        for (int i = 0; i < newComponents.size(); i++) {
            if (newComponents.get(i) instanceof CircuitComponent.VoltageSource src
                && "B0".equals(src.name())) {
                newComponents.set(i, src.withMaxAmplitude(biasT));
                break;
            }
        }
        return new CircuitDocument(
            circuit.id(), circuit.name(), newComponents, circuit.wires(), circuit.layout());
    }

    private static double averageBetween(List<ax.xz.mri.model.simulation.SignalTrace.Point> points,
                                         double tStartUs, double tEndUs) {
        double s = 0; int n = 0;
        for (var p : points) {
            if (p.tMicros() >= tStartUs && p.tMicros() <= tEndUs) { s += p.real(); n++; }
        }
        return n > 0 ? s / n : 0;
    }

    private static double sumBetween(List<ax.xz.mri.model.simulation.SignalTrace.Point> points,
                                     double tStartUs, double tEndUs) {
        double s = 0;
        for (var p : points) {
            if (p.tMicros() >= tStartUs && p.tMicros() <= tEndUs) s += p.real();
        }
        return s;
    }

    /**
     * Build a hand-crafted Ramsey pulse program directly as Segments + PulseSteps,
     * bypassing the ClipBaker so the test is independent of clip-to-step expansion.
     * Layout (all in units of {@link #DT_NS} = 5 ns):
     *
     * <pre>
     *   B0_offset    = DETUNING_T  for the entire run
     *   Laser        = 1 during [0, PUMP_NS) and [readStart, readStart + READ_NS)
     *   MW I         = MW_AMP_T during [pump+dark, pump+dark+π/2) and after τ for π/2
     * </pre>
     */
    private static Built buildRamsey(CircuitDocument circuit, double tauNs) {
        // Map source-name → channel offset using the order the SimulationCompiler
        // would use (CompiledCircuit's CompiledSource.channelOffset is what runtime
        // reads, but at this layer we mirror the order from circuit.voltageSources()).
        // We use the channel layout: each REAL/STATIC/GATE source has 1 channel,
        // ordered by appearance in circuit.components().
        java.util.LinkedHashMap<String, Integer> offsetByName = new java.util.LinkedHashMap<>();
        int offset = 0;
        for (var c : circuit.components()) {
            if (c instanceof CircuitComponent.VoltageSource src) {
                offsetByName.put(src.name(), offset);
                offset += src.kind().channelCount();
            }
        }
        int channelTotal = offset;
        Integer laserChannel = offsetByName.get("Laser");
        Integer mwIChannel   = offsetByName.get("MW I");

        double pumpUs   = PUMP_NS / 1000.0;
        double piHalfUs = T_PI_HALF_NS / 1000.0;
        double tauUs    = tauNs / 1000.0;
        double readUs   = READ_NS / 1000.0;
        double darkUs   = 0.010;     // 10 ns
        double totalUs  = pumpUs + darkUs + piHalfUs + tauUs + piHalfUs + darkUs + readUs;
        double dtSeconds = DT_NS * 1e-9;
        int nSteps = (int) Math.round(totalUs * 1000 / DT_NS);

        var steps = new ArrayList<PulseStep>(nSteps);
        for (int i = 0; i < nSteps; i++) {
            double tUs = (i * DT_NS) / 1000.0;
            var controls = new double[channelTotal];
            // Detuning is held through the B0 source's static amplitude (set by withB0Bias).
            // Laser pump.
            if (laserChannel != null && tUs < pumpUs) controls[laserChannel] = 1.0;
            // First π/2 pulse on MW I.
            double t1Start = pumpUs + darkUs;
            double t1End   = t1Start + piHalfUs;
            if (mwIChannel != null && tUs >= t1Start && tUs < t1End) controls[mwIChannel] = MW_AMP_T;
            // Second π/2 pulse on MW I.
            double t2Start = t1End + tauUs;
            double t2End   = t2Start + piHalfUs;
            if (mwIChannel != null && tUs >= t2Start && tUs < t2End) controls[mwIChannel] = MW_AMP_T;
            // Read window.
            double readStart = t2End + darkUs;
            double readEnd   = readStart + readUs;
            if (laserChannel != null && tUs >= readStart && tUs < readEnd) controls[laserChannel] = 1.0;
            steps.add(new PulseStep(controls, 0.0));
        }

        var pulse = List.of(new PulseSegment(steps));
        var segments = List.of(new Segment(dtSeconds, 0, nSteps));
        return new Built(segments, pulse);
    }

    private record Built(List<Segment> segments, List<PulseSegment> pulse) {}
}
