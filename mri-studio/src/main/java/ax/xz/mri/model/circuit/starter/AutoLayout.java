package ax.xz.mri.model.circuit.starter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentPosition;
import ax.xz.mri.model.circuit.Wire;

import java.util.List;

/**
 * Arranges circuit components into tidy columns grouped by kind.
 *
 * <p>Columns (left → right): voltage sources, switches, coils, probes,
 * grounds. Within each column, components stack vertically in declaration
 * order. A fresh schematic gets a legible starting point; the user can drag
 * components around afterwards and those edits persist in the layout.
 */
public final class AutoLayout {
    /** Horizontal spacing between columns (px). */
    public static final double COLUMN_SPACING = 220;

    /** Vertical spacing between components in a column (px). */
    public static final double ROW_SPACING = 140;

    /** Top-left origin where the first component is placed. */
    public static final double ORIGIN_X = 200;
    public static final double ORIGIN_Y = 160;

    private AutoLayout() {}

    public static CircuitLayout arrange(List<CircuitComponent> components, List<Wire> wires) {
        var layout = CircuitLayout.empty();
        int[] rowsPerColumn = new int[5];
        for (var component : components) {
            int col = columnFor(component);
            double x = ORIGIN_X + col * COLUMN_SPACING;
            double y = ORIGIN_Y + rowsPerColumn[col] * ROW_SPACING;
            rowsPerColumn[col]++;
            layout = layout.with(new ComponentPosition(component.id(), x, y, 0));
        }
        return layout;
    }

    /** Position an individual component relative to a layout, without disturbing the rest. */
    public static ComponentPosition defaultPositionFor(CircuitComponent component, CircuitLayout existing) {
        int col = columnFor(component);
        double x = ORIGIN_X + col * COLUMN_SPACING;
        // Find the highest y already in this column and place below.
        double maxY = ORIGIN_Y - ROW_SPACING;
        for (var pos : existing.positions().values()) {
            if (Math.abs(pos.x() - x) < COLUMN_SPACING / 2.0 && pos.y() > maxY) {
                maxY = pos.y();
            }
        }
        return new ComponentPosition(component.id(), x, maxY + ROW_SPACING, 0);
    }

    public static int columnFor(CircuitComponent component) {
        return switch (component) {
            case CircuitComponent.VoltageSource v -> 0;
            case CircuitComponent.SwitchComponent s -> 1;
            case CircuitComponent.Coil c -> 2;
            case CircuitComponent.Probe p -> 3;
            case CircuitComponent.Ground g -> 4;
            case CircuitComponent.Resistor r -> 1;
            case CircuitComponent.Capacitor c -> 1;
            case CircuitComponent.Inductor l -> 1;
            case CircuitComponent.IdealTransformer t -> 2;
        };
    }
}
