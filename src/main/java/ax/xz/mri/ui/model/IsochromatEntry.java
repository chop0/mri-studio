package ax.xz.mri.ui.model;

import ax.xz.mri.model.simulation.Trajectory;
import ax.xz.mri.model.simulation.Vec3;
import javafx.scene.paint.Color;

/**
 * An observation point — a single 3-D position in the FOV that the
 * Points pane displays. The point's trajectory is computed by the
 * simulator and cached here for the UI to render.
 *
 * <p>The position is always a {@link Vec3} in metres. The legacy
 * {@code (r, z)} pair is gone: nothing in the data model knows or cares
 * about cylindrical symmetry — that's a simulation-side optimisation, not
 * a UI invariant. Slice membership is similarly a UI-only affordance
 * (see {@link ax.xz.mri.ui.viewmodel.GeometryShadingService}) and doesn't
 * live on the entry.
 */
public record IsochromatEntry(
    IsochromatId id,
    Vec3 position,
    Color colour,
    boolean visible,
    String name,
    IsochromatOrigin origin,
    boolean locked,
    Trajectory trajectory
) {
    public IsochromatEntry {
        if (position == null) position = Vec3.ZERO;
    }

    public IsochromatEntry withTrajectory(Trajectory value) {
        return new IsochromatEntry(id, position, colour, visible, name, origin, locked, value);
    }

    public IsochromatEntry withPosition(Vec3 value) {
        return new IsochromatEntry(id, value, colour, visible, name, origin, locked, null);
    }

    public IsochromatEntry withVisible(boolean value) {
        return new IsochromatEntry(id, position, colour, value, name, origin, locked, trajectory);
    }

    public IsochromatEntry withName(String value) {
        return new IsochromatEntry(id, position, colour, visible, value, origin, locked, trajectory);
    }

    public IsochromatEntry withColour(Color value) {
        return new IsochromatEntry(id, position, value, visible, name, origin, locked, trajectory);
    }

    public IsochromatEntry withLocked(boolean value) {
        return new IsochromatEntry(id, position, colour, visible, name, origin, value, trajectory);
    }
}
