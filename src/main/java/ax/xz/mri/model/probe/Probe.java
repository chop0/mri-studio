package ax.xz.mri.model.probe;

import ax.xz.mri.model.substance.output.SpinOutput;

/**
 * A typed observable consumer.
 *
 * <p>{@code Probe<O>} reads the substance output channel of type {@code O}.
 * The sealed hierarchy + type parameter make probe routing checkable at
 * compile time: an {@link OpticalCounter} (a {@code Probe<PhotonClickRate>})
 * wired in the schematic to a substance that doesn't declare
 * {@link ax.xz.mri.model.substance.output.PhotonClickRate} fails before
 * the simulator ever runs.
 *
 * <p>Electrical probes read magnetic moments through the implicit / ambient
 * reciprocity bake-in — they don't need a wire to any substance; every
 * moment-emitting spin in the FOV contributes. Optical probes are wired
 * explicitly to a specific substance's optical output port.
 */
public sealed interface Probe<O extends SpinOutput> permits ElectricalProbe, OpticalCounter {

    /** The output type this probe consumes. Used for compile-time wire validation. */
    Class<O> consumes();

    /** A display label for the probe; doubles as the trace key in {@code MultiProbeSignalTrace}. */
    String name();
}
