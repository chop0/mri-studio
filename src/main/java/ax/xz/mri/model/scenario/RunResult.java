package ax.xz.mri.model.scenario;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.MultiProbeSignalTrace;

import java.util.List;
import java.util.Map;

/**
 * The complete payload produced by one execution of a sequence — the unified
 * "currency" of the analysis UI. Every pane that used to read
 * {@code BlochData} + {@code currentPulse} now reads from the active
 * {@code RunResult}.
 *
 * <p>Sealed because there are exactly two kinds of run: a {@link Simulation}
 * (Bloch-physics simulation that produces a full spatial state and from
 * which {@code SignalTraceComputer} derives probe traces lazily) and a
 * {@link Hardware} run (the device returns probe traces directly; no spatial
 * state is available to render in the phase / cross-section / Bloch-sphere
 * panes).
 */
public sealed interface RunResult {

    /** The pulse timeline that drove this run. Always present. */
    List<PulseSegment> pulse();

    /**
     * A simulation run. {@code output} carries the pre-computed spatial state
     * the simulator works against; signal traces are derived from it
     * downstream by {@code SignalTraceComputer}.
     */
    record Simulation(SimulationOutput output, List<PulseSegment> pulse) implements RunResult {}

    /**
     * A hardware run. {@code probeTraces} are what the device actually
     * returned; spatial state is unavailable. {@code deviceMetadata} is a
     * free-form map for plugin-specific provenance (firmware version,
     * timestamps, calibration, ...).
     */
    record Hardware(
        List<PulseSegment> pulse,
        MultiProbeSignalTrace probeTraces,
        Map<String, String> deviceMetadata
    ) implements RunResult {
        public Hardware {
            probeTraces = probeTraces == null ? MultiProbeSignalTrace.empty() : probeTraces;
            deviceMetadata = deviceMetadata == null ? Map.of() : Map.copyOf(deviceMetadata);
        }
    }
}
