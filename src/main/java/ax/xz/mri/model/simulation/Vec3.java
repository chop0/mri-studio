package ax.xz.mri.model.simulation;

/**
 * Immutable 3D vector used as the return type for eigenfield DSL scripts.
 *
 * <p>Scripts compute a normalised spatial field shape at a given position and
 * return a {@code Vec3}. The simulation scales by the field's amplitude to
 * produce the physical B-field in Tesla.
 *
 * <p>This class is deliberately small and dependency-free so Janino-compiled
 * scripts can instantiate it without bridging complications.
 */
public record Vec3(double x, double y, double z) {
    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public static final Vec3 X = new Vec3(1, 0, 0);
    public static final Vec3 Y = new Vec3(0, 1, 0);
    public static final Vec3 Z = new Vec3(0, 0, 1);

    public static Vec3 of(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    public double magnitude() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double magnitudeSquared() {
        return x * x + y * y + z * z;
    }

    public Vec3 scale(double factor) {
        return new Vec3(x * factor, y * factor, z * factor);
    }

    public Vec3 plus(Vec3 other) {
        return new Vec3(x + other.x, y + other.y, z + other.z);
    }

    public Vec3 minus(Vec3 other) {
        return new Vec3(x - other.x, y - other.y, z - other.z);
    }

    public double dot(Vec3 other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public Vec3 cross(Vec3 other) {
        return new Vec3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x
        );
    }

    public Vec3 normalised() {
        double m = magnitude();
        if (m < 1e-30) return ZERO;
        return new Vec3(x / m, y / m, z / m);
    }
}
