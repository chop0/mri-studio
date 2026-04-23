package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.util.MathUtil;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;

/** Camera and display state for the Bloch sphere. */
public class SphereViewModel {
    public final DoubleProperty theta = new SimpleDoubleProperty(0.6);
    public final DoubleProperty phi = new SimpleDoubleProperty(0.3);
    public final DoubleProperty zoom = new SimpleDoubleProperty(1.0);
    public final BooleanProperty showProjection = new SimpleBooleanProperty(false);

    public void setPreset(double thetaValue, double phiValue) {
        theta.set(thetaValue);
        phi.set(phiValue);
    }

    public void addTheta(double delta) {
        theta.set(theta.get() + delta);
    }

    public void addPhi(double delta) {
        phi.set(MathUtil.clamp(phi.get() + delta, -1.4, 1.4));
    }

    public void addZoom(double factor) {
        zoom.set(MathUtil.clamp(zoom.get() * factor, 0.5, 5.0));
    }

    public void reset() {
        theta.set(0.6);
        phi.set(0.3);
        zoom.set(1.0);
    }

    public SphereSnapshot captureSnapshot() {
        return new SphereSnapshot(theta.get(), phi.get(), zoom.get());
    }

    public void restoreSnapshot(SphereSnapshot snap) {
        theta.set(snap.theta());
        phi.set(snap.phi());
        zoom.set(snap.zoom());
    }

    public record SphereSnapshot(double theta, double phi, double zoom) {}
}
