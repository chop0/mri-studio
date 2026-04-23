package ax.xz.mri.optimisation;

import java.util.List;

/**
 * Flat per-point spatial data for the optimiser.
 *
 * <p>Each point carries its rotating-frame static Bz, initial magnetisation,
 * and an out-of-slice leakage weight. Per-dynamic-field eigenfield samples
 * live in {@link DynamicFieldSamples}; the primary receive coil's transverse
 * sensitivity and complex demodulation parameters drive
 * {@link ReceiveCoilSamples}.
 */
public record ProblemGeometry(
    double[] mx0,
    double[] my0,
    double[] mz0,
    double[] staticBz,
    double[] wOut,
    List<DynamicFieldSamples> dynamicFields,
    ReceiveCoilSamples primaryReceiveCoil,
    double gamma,
    double t1,
    double t2,
    int nr,
    int nz
) {
    public ProblemGeometry {
        dynamicFields = List.copyOf(dynamicFields);
        validateLength(mx0, my0, mz0, staticBz, wOut);
        int count = staticBz.length;
        for (var d : dynamicFields) {
            if (d.ex().length != count || d.ey().length != count || d.ez().length != count) {
                throw new IllegalArgumentException("dynamic-field samples must match point count");
            }
        }
        if (primaryReceiveCoil == null) {
            throw new IllegalArgumentException("primaryReceiveCoil must not be null — configure at least one ReceiveCoil");
        }
        if (primaryReceiveCoil.ex().length != count
                || primaryReceiveCoil.ey().length != count) {
            throw new IllegalArgumentException("receive-coil samples must match point count");
        }
        if (nr <= 0 || nz <= 0) throw new IllegalArgumentException("nr and nz must be positive");
    }

    public int pointCount() {
        return staticBz.length;
    }

    /** Per-point eigenfield samples for one dynamic (REAL or QUADRATURE) field. */
    public record DynamicFieldSamples(
        String name,
        int channelOffset,
        int channelCount,
        double deltaOmega,
        double[] ex,
        double[] ey,
        double[] ez
    ) {
        public boolean isQuadrature() { return channelCount == 2; }
    }

    /**
     * Per-point transverse sensitivity of a receive coil, plus its complex
     * demodulation parameters. The total coherent in-slice signal at each
     * step is
     * <pre>
     *   sr = Σ (ex·mx + ey·my)        si = Σ (ex·my − ey·mx)
     *   s  = gain · e^(i·phaseDeg·π/180) · (sr + i·si)
     * </pre>
     */
    public record ReceiveCoilSamples(
        String name,
        double gain,
        double phaseDeg,
        double[] ex,
        double[] ey
    ) {}

    private static void validateLength(double[]... arrays) {
        int length = arrays[0].length;
        for (var array : arrays) {
            if (array.length != length) {
                throw new IllegalArgumentException("all geometry arrays must have the same length");
            }
        }
    }
}
