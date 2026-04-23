package ax.xz.mri.model.circuit;

/**
 * Cosmetic position of a component on the schematic canvas.
 *
 * <p>{@code x} and {@code y} are in canvas-world coordinates (px). {@code rotation}
 * is currently ignored by the renderer; components are always drawn in their
 * canonical orientation.
 */
public record ComponentPosition(ComponentId id, double x, double y, int rotationQuarters) {
    public ComponentPosition {
        if (id == null) throw new IllegalArgumentException("ComponentPosition.id must not be null");
        if (!Double.isFinite(x) || !Double.isFinite(y))
            throw new IllegalArgumentException("ComponentPosition coordinates must be finite");
        rotationQuarters = ((rotationQuarters % 4) + 4) % 4;
    }
}
