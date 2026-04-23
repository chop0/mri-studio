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
 */
public final class ReceiveCoilMap {
    public final String name;
    public final double gain;
    public final double phaseDeg;
    public final double[][] ex;
    public final double[][] ey;
    public final double[][] ez;

    public ReceiveCoilMap(String name, double gain, double phaseDeg,
                          double[][] ex, double[][] ey, double[][] ez) {
        this.name = name;
        this.gain = gain;
        this.phaseDeg = phaseDeg;
        this.ex = ex;
        this.ey = ey;
        this.ez = ez;
    }
}
