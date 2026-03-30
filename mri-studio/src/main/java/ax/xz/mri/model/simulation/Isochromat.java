package ax.xz.mri.model.simulation;

import javafx.scene.paint.Color;

/**
 * One spatial sample point tracked through the Bloch simulation.
 * The field is named {@code colour} per Commonwealth convention.
 */
public record Isochromat(
    double     r,          // radial position (mm)
    double     z,          // axial position (mm)
    Color      colour,
    boolean    visible,
    String     name,
    Trajectory trajectory  // null before first simulation
) {
    public Isochromat withTrajectory(Trajectory t) {
        return new Isochromat(r, z, colour, visible, name, t);
    }

    public Isochromat withPosition(double newR, double newZ) {
        return new Isochromat(newR, newZ, colour, visible, name, null);
    }

    public Isochromat withVisible(boolean v) {
        return new Isochromat(r, z, colour, v, name, trajectory);
    }
}
