package ax.xz.mri.model.simulation.dsl;

import ax.xz.mri.model.simulation.Vec3;

/**
 * Compiled eigenfield script: a pure function from SI-unit position to a
 * normalised field vector.
 *
 * <p>Coordinate system: {@code x, y, z} are in metres. The returned
 * {@link Vec3} is the spatial field shape at unit amplitude — the simulator
 * multiplies by a field's physical amplitude to recover Tesla.
 */
@FunctionalInterface
public interface EigenfieldScript {
    Vec3 evaluate(double x, double y, double z);
}
