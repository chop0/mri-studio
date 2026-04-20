package ax.xz.mri.model.field;

/**
 * A dynamic field's sampled eigenfield and amplitude classification, ready
 * for per-step consumption by the Bloch simulator.
 *
 * <p>Each dynamic field occupies 1 ({@code REAL}) or 2 ({@code QUADRATURE})
 * pulse-sequence channels. {@link #channelOffset} gives the start index in
 * {@code PulseStep.controls}; {@link #channelCount} gives how many scalars
 * to read from there.
 *
 * <p>{@link #deltaOmega} = {@code 2π·carrierHz − ω_s}: the field's carrier
 * offset from the simulation frame. For QUADRATURE fields at the Larmor
 * carrier this is zero and no phase rotation is needed per step; otherwise
 * the simulator rotates (I, Q) by {@code deltaOmega · t} before applying.
 *
 * <p>Transverse components of the eigenfield are kept only when the field is
 * "slow enough" in the rotating frame; fast fields have their transverse
 * part absorbed into the static Bz map via Bloch–Siegert correction and
 * their {@link #ex}/{@link #ey} are zero.
 */
public final class DynamicFieldMap {
    public final String name;
    public final int channelOffset;
    public final int channelCount;  // 1 = REAL, 2 = QUADRATURE
    public final double carrierHz;
    public final double deltaOmega;  // 2π·carrierHz − γ·b0Ref, rad/s
    public final double[][] ex;       // eigenfield x-component at each grid point
    public final double[][] ey;
    public final double[][] ez;

    public DynamicFieldMap(
            String name,
            int channelOffset,
            int channelCount,
            double carrierHz,
            double deltaOmega,
            double[][] ex,
            double[][] ey,
            double[][] ez) {
        this.name = name;
        this.channelOffset = channelOffset;
        this.channelCount = channelCount;
        this.carrierHz = carrierHz;
        this.deltaOmega = deltaOmega;
        this.ex = ex;
        this.ey = ey;
        this.ez = ez;
    }

    public boolean isQuadrature() {
        return channelCount == 2;
    }
}
