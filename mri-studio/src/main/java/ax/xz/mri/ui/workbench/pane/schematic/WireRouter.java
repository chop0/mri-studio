package ax.xz.mri.ui.workbench.pane.schematic;

import java.util.List;

/** Produces orthogonal wire paths between two canvas points with a single bend. */
public final class WireRouter {
    private WireRouter() {}

    /**
     * Route a wire from {@code (x1, y1)} to {@code (x2, y2)} as an L-shape.
     *
     * <p>Heuristic: if the endpoints are closer horizontally than vertically,
     * bend at {@code (x1, midY)}; otherwise bend at {@code (midX, y1)}. Gives
     * a consistent look for typical schematic layouts.
     */
    public static List<double[]> route(double x1, double y1, double x2, double y2) {
        double dx = Math.abs(x2 - x1);
        double dy = Math.abs(y2 - y1);
        if (dx > dy) {
            double midX = (x1 + x2) / 2;
            return List.of(new double[]{x1, y1}, new double[]{midX, y1}, new double[]{midX, y2}, new double[]{x2, y2});
        } else {
            double midY = (y1 + y2) / 2;
            return List.of(new double[]{x1, y1}, new double[]{x1, midY}, new double[]{x2, midY}, new double[]{x2, y2});
        }
    }
}
