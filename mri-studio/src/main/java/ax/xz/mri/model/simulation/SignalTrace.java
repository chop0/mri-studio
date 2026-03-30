package ax.xz.mri.model.simulation;

import java.util.List;

/** Coherent signal magnitude as a function of time. */
public record SignalTrace(List<Point> points) {

    public record Point(double tMicros, double signal) {}
}
