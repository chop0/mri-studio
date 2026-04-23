package ax.xz.mri.ui.model;

import ax.xz.mri.model.simulation.Trajectory;
import javafx.scene.paint.Color;

/** Mutable-in-the-large UI row represented immutably for safer list updates. */
public record IsochromatEntry(
    IsochromatId id,
    double r,
    double z,
    Color colour,
    boolean visible,
    String name,
    boolean inSlice,
    IsochromatOrigin origin,
    boolean locked,
    Trajectory trajectory
) {
    public IsochromatEntry withTrajectory(Trajectory value) {
        return new IsochromatEntry(id, r, z, colour, visible, name, inSlice, origin, locked, value);
    }

    public IsochromatEntry withPosition(double newR, double newZ) {
        return new IsochromatEntry(id, newR, newZ, colour, visible, name, inSlice, origin, locked, null);
    }

    public IsochromatEntry withPosition(double newR, double newZ, boolean newInSlice) {
        return new IsochromatEntry(id, newR, newZ, colour, visible, name, newInSlice, origin, locked, null);
    }

    public IsochromatEntry withVisible(boolean value) {
        return new IsochromatEntry(id, r, z, colour, value, name, inSlice, origin, locked, trajectory);
    }

    public IsochromatEntry withName(String value) {
        return new IsochromatEntry(id, r, z, colour, visible, value, inSlice, origin, locked, trajectory);
    }

    public IsochromatEntry withColour(Color value) {
        return new IsochromatEntry(id, r, z, value, visible, name, inSlice, origin, locked, trajectory);
    }

    public IsochromatEntry withLocked(boolean value) {
        return new IsochromatEntry(id, r, z, colour, visible, name, inSlice, origin, value, trajectory);
    }
}
