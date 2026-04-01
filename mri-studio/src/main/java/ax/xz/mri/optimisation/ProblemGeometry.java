package ax.xz.mri.optimisation;

/** Flattened spatial geometry and weighting arrays used by the optimiser. */
public record ProblemGeometry(
    double[] mx0,
    double[] my0,
    double[] mz0,
    double[] dBz,
    double[] gxm,
    double[] gzm,
    double[] b1s,
    double[] wIn,
    double[] wOut,
    double sMax,
    double gamma,
    double t1,
    double t2,
    int nr,
    int nz
) {
    public ProblemGeometry {
        validateLength(mx0, my0, mz0, dBz, gxm, gzm, b1s, wIn, wOut);
        if (nr <= 0 || nz <= 0) throw new IllegalArgumentException("nr and nz must be positive");
    }

    public int pointCount() {
        return dBz.length;
    }

    private static void validateLength(double[]... arrays) {
        int length = arrays[0].length;
        for (var array : arrays) {
            if (array.length != length) throw new IllegalArgumentException("all geometry arrays must have the same length");
        }
    }
}
