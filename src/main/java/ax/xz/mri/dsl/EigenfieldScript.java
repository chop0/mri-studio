package ax.xz.mri.dsl;

import ax.xz.mri.model.simulation.Vec3;

/**
 * Compiled eigenfield script: a pure function from SI-unit position to a
 * normalised field-shape vector.
 *
 * <p>Users write full Java classes implementing this interface — default
 * package, no class-level modifiers, helper methods alongside {@code evaluate}.
 * The {@link ScriptEngine} compiles the source through Janino and instantiates
 * the discovered class.
 *
 * <p>Coordinate system: {@code x, y, z} are in metres. The returned
 * {@link Vec3} is the spatial field shape at unit amplitude — the simulator
 * multiplies by a coil's Tesla-per-amp sensitivity to recover physical
 * Tesla.
 */
public interface EigenfieldScript {
    Vec3 evaluate(double x, double y, double z);
}
