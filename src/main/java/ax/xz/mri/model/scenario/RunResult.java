package ax.xz.mri.model.scenario;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.MultiProbeSignalTrace;
import ax.xz.mri.service.simulation.compiled.CompiledSimulation;

import java.util.List;
import java.util.Map;

/**
 * The complete payload produced by one execution of a sequence — the unified
 * "currency" of the analysis UI. Every pane that consumes simulation or
 * hardware results reads from a {@code RunResult}.
 *
 * <p>Sealed because there are exactly two kinds of run: a {@link Simulation}
 * (the {@link CompiledSimulation} object is the simulation; consumers ask it
 * for whatever data they need) and a {@link Hardware} run (the device
 * returns probe traces directly; no spatial state is available to render in
 * the phase / cross-section / Bloch-sphere panes).
 */
public sealed interface RunResult {

    /** The pulse timeline that drove this run. Always present. */
    List<PulseSegment> pulse();

    /** A simulation run. The compiled simulation IS the simulation. */
    record Simulation(CompiledSimulation simulation, List<PulseSegment> pulse) implements RunResult {}

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
