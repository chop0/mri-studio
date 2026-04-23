package ax.xz.mri.model.simulation;

import java.util.List;

/**
 * Time-domain signal observed by a receive coil. {@link Point#real()} and
 * {@link Point#imag()} carry the complex demodulated amplitude; {@link
 * Point#signal()} is the derived magnitude.
 */
public record SignalTrace(List<Point> points) {

    public record Point(double tMicros, double real, double imag) {
        public double signal() {
            return Math.hypot(real, imag);
        }
    }
}
