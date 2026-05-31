package ax.xz.mri.model.nv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Description of how an NV array is laid out. Produces a deterministic
 * {@link List}{@code <}{@link NvCentre}{@code >} via {@link #generate()}.
 *
 * <p>The {@code seed} field makes random layouts (e.g. {@link NvArrayShape#LINEAR_X_RANDOM})
 * reproducible across runs. Generation never allocates inside the simulator
 * loop — callers are expected to cache the {@code List<NvCentre>} after one
 * {@code generate()} call.
 */
public record NvArrayGeometry(
    NvArrayShape shape,
    int n,
    double lengthMetres,
    double depthMetres,
    NvAxis axis,
    long seed,
    List<NvCentre> customCentres
) {

    public NvArrayGeometry {
        if (shape == null) throw new IllegalArgumentException("NvArrayGeometry.shape must be non-null");
        if (n < 1) throw new IllegalArgumentException("NvArrayGeometry.n must be >= 1, got " + n);
        if (!Double.isFinite(lengthMetres) || lengthMetres <= 0) {
            throw new IllegalArgumentException("NvArrayGeometry.lengthMetres must be positive, got " + lengthMetres);
        }
        if (!Double.isFinite(depthMetres)) {
            throw new IllegalArgumentException("NvArrayGeometry.depthMetres must be finite, got " + depthMetres);
        }
        if (axis == null) axis = NvAxis.AXIS_PLUS_Z;
        if (shape == NvArrayShape.CUSTOM) {
            if (customCentres == null || customCentres.isEmpty()) {
                throw new IllegalArgumentException("NvArrayGeometry.CUSTOM requires non-empty customCentres");
            }
            customCentres = List.copyOf(customCentres);
        } else {
            customCentres = customCentres == null ? List.of() : List.copyOf(customCentres);
        }
    }

    /** Convenience constructor — generated shape with no custom centres. */
    public NvArrayGeometry(NvArrayShape shape, int n, double lengthMetres,
                           double depthMetres, NvAxis axis, long seed) {
        this(shape, n, lengthMetres, depthMetres, axis, seed, List.of());
    }

    /**
     * Deterministically produce the list of {@link NvCentre}s described by
     * this geometry. The same {@code (shape, n, length, depth, axis, seed,
     * customCentres)} always returns the same list.
     */
    public List<NvCentre> generate() {
        return switch (shape) {
            case LINEAR_X_RANDOM  -> linearXRandom();
            case LINEAR_X_UNIFORM -> linearXUniform();
            case GRID_XY          -> gridXy();
            case CUSTOM           -> customCentres;
        };
    }

    private List<NvCentre> linearXRandom() {
        var rng = new Random(seed);
        var xs = new double[n];
        for (int i = 0; i < n; i++) xs[i] = (rng.nextDouble() - 0.5) * lengthMetres;
        Arrays.sort(xs);
        var list = new ArrayList<NvCentre>(n);
        for (int i = 0; i < n; i++) list.add(new NvCentre(xs[i], 0.0, depthMetres, axis));
        return List.copyOf(list);
    }

    private List<NvCentre> linearXUniform() {
        var list = new ArrayList<NvCentre>(n);
        double step = lengthMetres / n;
        double x0 = -lengthMetres / 2.0 + step / 2.0;
        for (int i = 0; i < n; i++) list.add(new NvCentre(x0 + i * step, 0.0, depthMetres, axis));
        return List.copyOf(list);
    }

    private List<NvCentre> gridXy() {
        // Treat n as per-axis count: total NVs = n × n.
        var list = new ArrayList<NvCentre>(n * n);
        double step = lengthMetres / n;
        double p0 = -lengthMetres / 2.0 + step / 2.0;
        for (int iy = 0; iy < n; iy++) {
            for (int ix = 0; ix < n; ix++) {
                list.add(new NvCentre(p0 + ix * step, p0 + iy * step, depthMetres, axis));
            }
        }
        return List.copyOf(list);
    }
}
