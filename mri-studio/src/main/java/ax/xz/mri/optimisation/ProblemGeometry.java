package ax.xz.mri.optimisation;

import java.util.List;

/**
 * Flat per-point spatial data for the optimiser.
 *
 * <p>Each point carries its rotating-frame static Bz, initial magnetisation,
 * and weights. Per-dynamic-field eigenfield samples at each point live in
 * {@link DynamicFieldSamples}, indexed alongside
 * {@link ax.xz.mri.model.field.FieldMap#dynamicFields} in pulse-channel
 * order.
 */
public record ProblemGeometry(
    double[] mx0,
    double[] my0,
    double[] mz0,
    double[] staticBz,
    double[] wIn,
    double[] wOut,
    List<DynamicFieldSamples> dynamicFields,
    double sMax,
    double gamma,
    double t1,
    double t2,
    int nr,
    int nz
) {
    public ProblemGeometry {
        dynamicFields = List.copyOf(dynamicFields);
        validateLength(mx0, my0, mz0, staticBz, wIn, wOut);
        if (nr <= 0 || nz <= 0) throw new IllegalArgumentException("nr and nz must be positive");
        int count = staticBz.length;
        for (var d : dynamicFields) {
            if (d.ex().length != count || d.ey().length != count || d.ez().length != count) {
                throw new IllegalArgumentException("dynamic-field samples must match point count");
            }
        }
    }

    public int pointCount() {
        return staticBz.length;
    }

    /** Per-point eigenfield samples for one dynamic (REAL or QUADRATURE) field. */
    public record DynamicFieldSamples(
        String name,
        int channelOffset,
        int channelCount,     // 1 = REAL, 2 = QUADRATURE
        double deltaOmega,    // rotating-frame offset (rad/s)
        double[] ex,
        double[] ey,
        double[] ez
    ) {
        public boolean isQuadrature() { return channelCount == 2; }
    }

    private static void validateLength(double[]... arrays) {
        int length = arrays[0].length;
        for (var array : arrays) {
            if (array.length != length) {
                throw new IllegalArgumentException("all geometry arrays must have the same length");
            }
        }
    }
}
