package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.simulation.Vec3;

/**
 * A planar slice through the FOV.
 *
 * <p>Defined by an in-plane {@code origin} (the plane's reference point) and a
 * unit-length {@code normal}. The plane satisfies {@code n · (x − origin) = 0}.
 * Two in-plane basis vectors {@link #u} and {@link #v} span the plane so the
 * heatmap pane can render row-by-row over the slice without knowing the
 * orientation.
 *
 * <p>The constructor normalises the normal and derives the basis vectors
 * deterministically — a fresh {@code SlicePlane(origin, anyNonZeroNormal)}
 * always produces the same {@code u, v} for a given normal direction. This
 * matters because the heatmap pane keys its sample cache by {@code SlicePlane}
 * identity; non-deterministic basis derivation would invalidate the cache on
 * every refresh.
 */
public record SlicePlane(Vec3 origin, Vec3 normal, Vec3 u, Vec3 v) {

    public SlicePlane {
        if (origin == null) origin = Vec3.ZERO;
        if (normal == null || normal.magnitude() == 0) {
            throw new IllegalArgumentException("SlicePlane.normal must be non-zero");
        }
        // Auto-derive u/v if caller passed null — the public factory below is
        // the usual entry point.
        if (u == null || v == null) {
            normal = normal.normalised();
            var basis = deriveBasis(normal);
            u = basis[0];
            v = basis[1];
        }
    }

    /** Build a slice plane from an arbitrary (non-zero) normal. {@code u, v} are derived deterministically. */
    public static SlicePlane of(Vec3 origin, Vec3 normal) {
        if (normal == null || normal.magnitude() == 0) {
            throw new IllegalArgumentException("normal must be non-zero");
        }
        Vec3 n = normal.normalised();
        var basis = deriveBasis(n);
        return new SlicePlane(origin == null ? Vec3.ZERO : origin, n, basis[0], basis[1]);
    }

    /** Slice perpendicular to +Z through the origin (i.e. {@code z = 0}). */
    public static SlicePlane axisZ() { return of(Vec3.ZERO, Vec3.Z); }
    /** Slice perpendicular to +Y through the origin. */
    public static SlicePlane axisY() { return of(Vec3.ZERO, Vec3.Y); }
    /** Slice perpendicular to +X through the origin. */
    public static SlicePlane axisX() { return of(Vec3.ZERO, Vec3.X); }

    /** Signed perpendicular distance from {@code p} to this plane: {@code n · (p − origin)}. */
    public double signedDistance(Vec3 p) {
        return normal.dot(p.minus(origin));
    }

    /** Returns the projection of {@code p} onto the plane (closest point on the plane). */
    public Vec3 project(Vec3 p) {
        double d = signedDistance(p);
        return p.minus(normal.scale(d));
    }

    /** A point in the plane at the given (u, v) offsets from {@link #origin}. */
    public Vec3 sampleAt(double uMetres, double vMetres) {
        return origin.plus(u.scale(uMetres)).plus(v.scale(vMetres));
    }

    /** Plane translated along its normal by {@code offsetMetres}. The {@code u, v} basis is preserved. */
    public SlicePlane withOffsetAlongNormal(double offsetMetres) {
        return new SlicePlane(
            origin.plus(normal.scale(offsetMetres)), normal, u, v);
    }

    /** Plane with a new origin (basis unchanged). */
    public SlicePlane withOrigin(Vec3 newOrigin) {
        return new SlicePlane(newOrigin, normal, u, v);
    }

    /**
     * Deterministic right-handed basis derivation:
     * {@code u} is chosen orthogonal to {@code n} (preferring the world X
     * axis as the reference, falling back to Y when {@code n ≈ ±X}), and
     * {@code v = n × u}.
     */
    private static Vec3[] deriveBasis(Vec3 n) {
        Vec3 ref = (Math.abs(n.x()) < 0.9) ? Vec3.X : Vec3.Y;
        Vec3 u = ref.minus(n.scale(n.dot(ref))).normalised();
        Vec3 v = n.cross(u).normalised();
        return new Vec3[]{u, v};
    }
}
