package ax.xz.mri.model.probe;

import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.substance.output.PhotonClickRate;

/**
 * A photon-counter probe wired explicitly to one of a substance's optical
 * output ports. Counts Poisson clicks at the wired channel's instantaneous
 * rate across the integration window the probe is sampling.
 *
 * <p>Unlike {@link ElectricalProbe}, optical probes are wired: a
 * connection from a substance's {@code "clicks_<channel>"} port to the
 * counter's input. The compiler resolves the wire at sim-compile time and
 * binds the probe to that substance's emission rate. Probes wired to
 * substances that don't declare {@link PhotonClickRate} fail at the
 * schematic check; probes wired to channels the substance doesn't expose
 * also fail.
 *
 * <p>{@link #seed} drives the Poisson sampler — together with the wired
 * substance's own shot RNG, all stochasticity in optical readout is
 * deterministic given the simulation seeds.
 */
public record OpticalCounter(
    ComponentId id,
    String name,
    String wiredSubstanceId,
    String wiredChannelName,
    double quantumEfficiency,
    double darkRateHz,
    long seed
) implements Probe<PhotonClickRate> {

    public OpticalCounter {
        if (wiredSubstanceId == null || wiredSubstanceId.isBlank())
            throw new IllegalArgumentException("OpticalCounter.wiredSubstanceId must be set");
        if (wiredChannelName == null || wiredChannelName.isBlank())
            throw new IllegalArgumentException("OpticalCounter.wiredChannelName must be set");
        if (!(quantumEfficiency >= 0 && quantumEfficiency <= 1))
            throw new IllegalArgumentException("OpticalCounter.quantumEfficiency must be in [0, 1]");
        if (!(darkRateHz >= 0))
            throw new IllegalArgumentException("OpticalCounter.darkRateHz must be non-negative");
    }

    @Override public Class<PhotonClickRate> consumes() { return PhotonClickRate.class; }
}
