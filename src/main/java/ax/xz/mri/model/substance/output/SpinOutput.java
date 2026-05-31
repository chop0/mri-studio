package ax.xz.mri.model.substance.output;

/**
 * A typed observable a spin emits. Probes are parametric on which
 * {@code SpinOutput} they consume — {@code Probe<MagneticMoment>}
 * pickups via reciprocity, {@code Probe<PhotonClickRate>} counts photons.
 *
 * <p>The sealed hierarchy makes the routing checkable at compile time:
 * an {@link ax.xz.mri.model.probe.OpticalCounter} wired to a substance
 * that doesn't declare {@link PhotonClickRate} fails to type-check at
 * the schematic level.
 */
public sealed interface SpinOutput permits MagneticMoment, PhotonClickRate {}
