package ax.xz.mri.ui.workbench.pane.schematic;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.ui.workbench.pane.schematic.presenter.ComponentPresenters;

import java.util.List;

/**
 * Layout measurements for a single circuit component on the schematic canvas.
 *
 * <p>Coordinates are in canvas-world pixels relative to the component's
 * placement position. Every component kind has a canonical geometry (size +
 * terminal positions) so hit-testing and wire attachment both reference the
 * same source of truth.
 *
 * <p>The geometry itself lives on the per-kind
 * {@link ax.xz.mri.ui.workbench.pane.schematic.presenter.ComponentPresenter};
 * {@link #of(CircuitComponent)} is a convenience shortcut that callers can
 * keep using.
 */
public record ComponentGeometry(double width, double height, List<Terminal> terminals) {

    public record Terminal(String port, double xOffset, double yOffset) {}

    /** Signed half-width left of center. */
    public double halfWidth() { return width / 2; }
    /** Signed half-height above center. */
    public double halfHeight() { return height / 2; }

    public Terminal terminal(String port) {
        for (var t : terminals) if (t.port().equals(port)) return t;
        throw new IllegalArgumentException("Unknown port: " + port);
    }

    public static ComponentGeometry of(CircuitComponent component) {
        return ComponentPresenters.of(component).geometry();
    }
}
