package ax.xz.mri.service.procedure;

import ax.xz.mri.dsl.BakedSequence;
import ax.xz.mri.model.simulation.MultiProbeSignalTrace;

/**
 * Where a {@link ax.xz.mri.dsl.Script} sends pulse sequences and reads
 * observations.
 *
 * <p>Sealed over the two backends a script can run against — a
 * {@link SimulatorObservationSource} wrapping a {@link
 * ax.xz.mri.service.simulation.compiled.CompiledSimulation}, or a
 * {@link HardwareObservationSource} wrapping a live device. Both expose
 * exactly the same surface: hand the source a baked sequence, get back one
 * signal trace per probe. The harness drives either kind through the same
 * loop. <em>This interface deliberately has nothing NV-specific or
 * Bloch-specific on it</em> — scripts express their experiments as pulse
 * sequences, the source returns the resulting traces, and the script
 * decodes whatever observable it needs (M, k-space coefficients, pulse
 * fidelity, …) from the trace. Adding a "runRamsey" would have forced
 * HardwareObservationSource to either build the pulse sequence internally
 * — duplicating script logic — or throw — pretending an abstraction works
 * when it doesn't.
 */
public sealed interface ObservationSource permits SimulatorObservationSource, HardwareObservationSource {

    /** A short label shown in the harness completion notification ("Simulator", "RedPitaya RP-EFGH"). */
    String displayName();

    /**
     * Execute the supplied {@link BakedSequence}. Returns one trace per probe
     * — keyed identically across sim and hardware so the script's observation
     * parsing is source-agnostic.
     */
    MultiProbeSignalTrace run(BakedSequence seq);
}
