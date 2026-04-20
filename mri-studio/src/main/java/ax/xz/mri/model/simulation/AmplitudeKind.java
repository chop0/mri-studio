package ax.xz.mri.model.simulation;

/**
 * How a field's amplitude schedule is interpreted.
 *
 * <p>This plus the field's {@code carrierHz} fully determines how the simulator
 * couples the field's amplitude to the rotating-frame B-field integration —
 * there is no separate {@code ControlType} or {@code basebandFrequencyHz}
 * split, and no inferred "B0 slot" rule.
 *
 * <p>Relation to pulse-sequence channels:
 * <ul>
 *   <li>{@link #STATIC} — 0 channels. Amplitude is fixed at the field's
 *       {@code maxAmplitude}; the pulse sequence has nothing to say.</li>
 *   <li>{@link #REAL} — 1 channel. One real scalar per time step, baseband
 *       at {@code carrierHz}. Typical use: gradients (carrier = 0).</li>
 *   <li>{@link #QUADRATURE} — 2 channels (I, Q). Complex baseband at
 *       {@code carrierHz}. Typical use: RF at Larmor.</li>
 * </ul>
 */
public enum AmplitudeKind {
    /** Constant amplitude at {@code maxAmplitude}. No pulse-sequence channels. */
    STATIC(0),

    /** Real baseband. One pulse-sequence channel. */
    REAL(1),

    /** Complex baseband (I, Q). Two pulse-sequence channels. */
    QUADRATURE(2);

    private final int channelCount;

    AmplitudeKind(int channelCount) {
        this.channelCount = channelCount;
    }

    /** Number of pulse-sequence control scalars this kind consumes per step. */
    public int channelCount() {
        return channelCount;
    }
}
