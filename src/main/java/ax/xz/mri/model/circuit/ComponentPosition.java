package ax.xz.mri.model.circuit;

/**
 * Cosmetic position of a component on the schematic canvas.
 *
 * <p>{@code x} and {@code y} are in canvas-world coordinates (px).
 * {@code rotationQuarters} is the number of clockwise 90-degree turns applied
 * to the canonical drawing. {@code mirrored} flips horizontally around the
 * component's center (applied before rotation).
 */
public record ComponentPosition(
    ComponentId id,
    double x,
    double y,
    int rotationQuarters,
    boolean mirrored
) {
    public ComponentPosition {
        if (id == null) throw new IllegalArgumentException("ComponentPosition.id must not be null");
        if (!Double.isFinite(x) || !Double.isFinite(y))
            throw new IllegalArgumentException("ComponentPosition coordinates must be finite");
        rotationQuarters = ((rotationQuarters % 4) + 4) % 4;
    }

    /** Convenience for un-mirrored positions. */
    public ComponentPosition(ComponentId id, double x, double y, int rotationQuarters) {
        this(id, x, y, rotationQuarters, false);
    }

    public ComponentPosition withRotationQuarters(int newRotation) {
        return new ComponentPosition(id, x, y, newRotation, mirrored);
    }

    public ComponentPosition withMirrored(boolean newMirrored) {
        return new ComponentPosition(id, x, y, rotationQuarters, newMirrored);
    }

    /** Apply the (mirror → rotate) transform to a local offset (x, y). */
    public double[] transformOffset(double localX, double localY) {
        double mx = mirrored ? -localX : localX;
        double my = localY;
        double angle = rotationQuarters * Math.PI / 2.0;
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        return new double[]{mx * c - my * s, mx * s + my * c};
    }
}
