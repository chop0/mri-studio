package ax.xz.mri.model.field;

/**
 * Runtime spatial sensitivity of a single receive coil.
 *
 * <p>Sampled from a {@link ax.xz.mri.model.simulation.ReceiveCoil}'s eigenfield
 * onto the (r, z) grid. The simulator integrates
 * <pre>
 *   s(t) = gain · e^(i·phaseDeg·π/180) · Σ (eₓ − i·e_y) · (Mₓ + i·M_y) dV
 * </pre>
 * per time step per coil.
 *
 * <p>{@link #acquisitionGateOffset} is the channel offset of the optional
 * acquisition gate in {@code PulseStep.controls}, or {@code -1} if the coil
 * is always active. When set, the coil emits zero whenever the gate reads 0.
 */
public final class ReceiveCoilMap {
    public final String name;
    public final double gain;
    public final double phaseDeg;
    public final int acquisitionGateOffset;
    public final double[][] ex;
    public final double[][] ey;
    public final double[][] ez;

    public ReceiveCoilMap(String name, double gain, double phaseDeg, int acquisitionGateOffset,
                          double[][] ex, double[][] ey, double[][] ez) {
        this.name = name;
        this.gain = gain;
        this.phaseDeg = phaseDeg;
        this.acquisitionGateOffset = acquisitionGateOffset;
        this.ex = ex;
        this.ey = ey;
        this.ez = ez;
    }

    public ReceiveCoilMap(String name, double gain, double phaseDeg,
                          double[][] ex, double[][] ey, double[][] ez) {
        this(name, gain, phaseDeg, -1, ex, ey, ez);
    }
}
