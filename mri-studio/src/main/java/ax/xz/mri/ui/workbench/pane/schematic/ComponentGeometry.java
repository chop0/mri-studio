package ax.xz.mri.ui.workbench.pane.schematic;

import ax.xz.mri.model.circuit.CircuitComponent;

import java.util.List;

/**
 * Layout measurements for a single circuit component on the schematic canvas.
 *
 * <p>Coordinates are in canvas-world pixels relative to the component's
 * placement position. Every component kind has a canonical geometry (size +
 * terminal positions) so hit-testing and wire attachment both reference the
 * same source of truth.
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
        return switch (component) {
            case CircuitComponent.VoltageSource ignored -> new ComponentGeometry(90, 70, List.of(
                new Terminal("out", 45, 0),
                new Terminal("active", 0, 35)
            ));
            case CircuitComponent.SwitchComponent ignored -> new ComponentGeometry(90, 70, List.of(
                new Terminal("a", -45, 0),
                new Terminal("b", 45, 0),
                new Terminal("ctl", 0, 35)
            ));
            case CircuitComponent.Coil ignored -> SINGLE_TERMINAL_IN;
            case CircuitComponent.Probe ignored -> SINGLE_TERMINAL_IN;
            case CircuitComponent.Resistor ignored -> TWO_TERMINAL_HORIZONTAL;
            case CircuitComponent.Capacitor ignored -> TWO_TERMINAL_HORIZONTAL;
            case CircuitComponent.Inductor ignored -> TWO_TERMINAL_HORIZONTAL;
            case CircuitComponent.ShuntResistor ignored -> SINGLE_TERMINAL_IN;
            case CircuitComponent.ShuntCapacitor ignored -> SINGLE_TERMINAL_IN;
            case CircuitComponent.ShuntInductor ignored -> SINGLE_TERMINAL_IN;
            case CircuitComponent.IdealTransformer ignored -> new ComponentGeometry(100, 80, List.of(
                new Terminal("pa", -50, -20),
                new Terminal("pb", -50, 20),
                new Terminal("sa", 50, -20),
                new Terminal("sb", 50, 20)
            ));
        };
    }

    private static final ComponentGeometry TWO_TERMINAL_HORIZONTAL = new ComponentGeometry(90, 60, List.of(
        new Terminal("a", -45, 0),
        new Terminal("b", 45, 0)
    ));

    private static final ComponentGeometry SINGLE_TERMINAL_IN = new ComponentGeometry(90, 60, List.of(
        new Terminal("in", -45, 0)
    ));
}
