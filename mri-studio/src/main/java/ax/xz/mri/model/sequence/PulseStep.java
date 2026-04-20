package ax.xz.mri.model.sequence;

import java.util.Arrays;

/**
 * One time-step of a pulse sequence: amplitudes for each control channel
 * plus an RF gate flag.
 *
 * <p>{@code controls} has length equal to the owning simulation config's
 * {@link ax.xz.mri.model.simulation.SimulationConfig#totalChannelCount()} —
 * one scalar per {@code REAL} field, two consecutive scalars per
 * {@code QUADRATURE} field (I, Q). {@code STATIC} fields contribute no
 * channels.
 *
 * <p>{@code rfGate} is a hint for the simulator fast-path: when false, no
 * transverse amplitudes are active and the step degenerates to a pure Bz
 * rotation. It is conservative — it may be {@code true} with zero transverse
 * amplitude, which just means the slow-path runs with degenerate input.
 */
public record PulseStep(double[] controls, double rfGate) {

    public PulseStep {
        if (controls == null) controls = new double[0];
    }

    /** Convenience: integer gate flag. */
    public boolean isRfOn() {
        return rfGate >= 0.5;
    }

    /** Number of control channels in this step. */
    public int channelCount() {
        return controls.length;
    }

    /** Read a control scalar; returns 0 if {@code index} is out of range. */
    public double control(int index) {
        return (index >= 0 && index < controls.length) ? controls[index] : 0.0;
    }

    /**
     * Deep-copy this step. The backing array is cloned, so mutations to the
     * copy don't leak into the original.
     */
    public PulseStep copy() {
        return new PulseStep(controls.clone(), rfGate);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PulseStep other)) return false;
        return rfGate == other.rfGate && Arrays.equals(controls, other.controls);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(controls) + Double.hashCode(rfGate);
    }
}
