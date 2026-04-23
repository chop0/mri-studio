package ax.xz.mri.model.simulation;

/**
 * How a {@link DrivePath}'s amplitude schedule is interpreted.
 *
 * <p>This, together with the path's {@code carrierHz}, fully determines how
 * the simulator couples the amplitude to the rotating-frame B-field
 * integration (for transmit paths) or to the observational layer (for gate
 * paths). There is no inferred "B0 slot" rule — statics are just drive paths
 * with a fixed amplitude.
 *
 * <p>Pulse-sequence channels per step:
 * <ul>
 *   <li>{@link #STATIC} — 0 channels. Amplitude pinned to
 *       {@link DrivePath#maxAmplitude()}; no timeline control.</li>
 *   <li>{@link #REAL} — 1 channel. One real scalar per step, baseband at
 *       {@code carrierHz}. Typical: gradients (carrier = 0).</li>
 *   <li>{@link #QUADRATURE} — 2 channels (I, Q). Complex baseband at
 *       {@code carrierHz}. Typical: RF at Larmor.</li>
 *   <li>{@link #GATE} — 1 channel. A 0/1-valued digital signal. Drives
 *       switches and acquisition windows; does not contribute to B.</li>
 * </ul>
 */
public enum AmplitudeKind {
    STATIC(0),
    REAL(1),
    QUADRATURE(2),
    GATE(1);

    private final int channelCount;

    AmplitudeKind(int channelCount) {
        this.channelCount = channelCount;
    }

    public int channelCount() {
        return channelCount;
    }
}
