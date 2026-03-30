package ax.xz.mri.state;

import ax.xz.mri.util.MathUtil;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;

/** Bloch-sphere camera: azimuth, elevation, zoom. */
public class CameraState {
    public final DoubleProperty theta = new SimpleDoubleProperty(0.6);   // azimuth (rad)
    public final DoubleProperty phi   = new SimpleDoubleProperty(0.4);   // elevation (rad)
    public final DoubleProperty zoom  = new SimpleDoubleProperty(1.0);

    public void addTheta(double d) { theta.set(theta.get() + d); }

    public void addPhi(double d) {
        phi.set(MathUtil.clamp(phi.get() + d, -1.4, 1.4));
    }

    public void addZoom(double factor) {
        zoom.set(MathUtil.clamp(zoom.get() * factor, 0.5, 5.0));
    }
}
