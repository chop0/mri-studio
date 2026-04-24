package ax.xz.mri.model.circuit.compile;

/**
 * How a two-port complex block (a {@link ax.xz.mri.model.circuit.CircuitComponent.Modulator}
 * or {@link ax.xz.mri.model.circuit.CircuitComponent.Mixer}) maps between
 * its scalar ports and the complex phasor it produces / consumes.
 *
 * <p>{@link #IQ}: rectangular — the two scalars are the real (in-phase)
 * and imaginary (quadrature) parts of the phasor. Linear in each channel.
 *
 * <p>{@link #MAG_PHASE}: polar — the first scalar is the magnitude and
 * the second is the phase in radians. Reads cleanly on an oscilloscope
 * and for amplitude-dominant pulses but non-linear in phase.
 */
public enum ComplexPairFormat {
    IQ,
    MAG_PHASE
}
