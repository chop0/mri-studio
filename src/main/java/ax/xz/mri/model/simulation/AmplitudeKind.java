package ax.xz.mri.model.simulation;

/**
 * How a voltage source's amplitude schedule is interpreted. Every source
 * emits a single scalar per step; if you want an I/Q quadrature drive,
 * compose two {@link #REAL} sources (one for I, one for Q) and feed them
 * into a {@link ax.xz.mri.model.circuit.CircuitComponent.Modulator} block
 * that upconverts their envelope to the carrier.
 *
 * <p>Pulse-sequence channels per step:
 * <ul>
 *   <li>{@link #STATIC} — 0 channels. Amplitude pinned to the source's
 *       {@code maxAmplitude}; no timeline control.</li>
 *   <li>{@link #REAL} — 1 channel. One real scalar per step.</li>
 *   <li>{@link #GATE} — 1 channel. A 0/1-valued digital signal. Drives
 *       switches and acquisition windows; does not contribute to B.</li>
 * </ul>
 */
public enum AmplitudeKind {
    STATIC(0),
    REAL(1),
    GATE(1);

    private final int channelCount;

    AmplitudeKind(int channelCount) {
        this.channelCount = channelCount;
    }

    public int channelCount() {
        return channelCount;
    }
}
