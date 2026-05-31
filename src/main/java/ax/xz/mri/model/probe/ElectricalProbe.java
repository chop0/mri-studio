package ax.xz.mri.model.probe;

import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.substance.output.MagneticMoment;

/**
 * An MRI-style receive probe: a node in the circuit graph whose voltage
 * follows the reciprocity-induced EMF from every magnetic-moment-emitting
 * spin in the FOV.
 *
 * <p>The probe sits on a circuit node (resolved at {@link ComponentId
 * compile time}). Its voltage is read from the per-step MNA solve after
 * the reciprocity EMF has been stamped into all coil branches.
 * {@link #gain} and {@link #demodPhaseDeg} are post-solve cosmetic
 * adjustments matching the legacy compiled-probe API.
 *
 * <p>Coupling is implicit: every {@link MagneticMoment} emitter in the
 * sim contributes, weighted by its per-spin per-coil reciprocity weight.
 * No wires are involved.
 */
public record ElectricalProbe(
    ComponentId id,
    String name,
    double gain,
    double demodPhaseDeg,
    double loadImpedanceOhms
) implements Probe<MagneticMoment> {

    public ElectricalProbe {
        if (!(loadImpedanceOhms > 0)) {
            throw new IllegalArgumentException("ElectricalProbe.loadImpedanceOhms must be positive");
        }
    }

    @Override public Class<MagneticMoment> consumes() { return MagneticMoment.class; }
}
