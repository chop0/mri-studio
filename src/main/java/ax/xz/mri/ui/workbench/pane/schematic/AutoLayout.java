package ax.xz.mri.ui.workbench.pane.schematic;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitLayout;
import ax.xz.mri.model.circuit.ComponentPosition;
import ax.xz.mri.model.circuit.Wire;
import ax.xz.mri.ui.workbench.pane.schematic.presenter.ComponentPresenters;

import java.util.List;

/**
 * Arranges circuit components into tidy columns grouped by kind.
 *
 * <p>Column assignment lives on the per-kind
 * {@link ax.xz.mri.ui.workbench.pane.schematic.presenter.ComponentPresenter};
 * this class is pure layout glue.
 */
public final class AutoLayout {
    /** Horizontal spacing between columns (px). */
    public static final double COLUMN_SPACING = 220;

    /** Vertical spacing between components in a column (px). */
    public static final double ROW_SPACING = 140;

    /** Top-left origin where the first component is placed. */
    public static final double ORIGIN_X = 200;
    public static final double ORIGIN_Y = 160;

    private static final int COLUMN_COUNT = 4;

    private AutoLayout() {}

    public static CircuitLayout arrange(List<CircuitComponent> components, List<Wire> wires) {
        var layout = CircuitLayout.empty();
        int[] rowsPerColumn = new int[COLUMN_COUNT];
        for (var component : components) {
            int col = Math.min(columnFor(component), COLUMN_COUNT - 1);
            double x = ORIGIN_X + col * COLUMN_SPACING;
            double y = ORIGIN_Y + rowsPerColumn[col] * ROW_SPACING;
            rowsPerColumn[col]++;
            layout = layout.with(new ComponentPosition(component.id(), x, y, 0));
        }
        return layout;
    }

    /** Position an individual component relative to a layout, without disturbing the rest. */
    public static ComponentPosition defaultPositionFor(CircuitComponent component, CircuitLayout existing) {
        int col = Math.min(columnFor(component), COLUMN_COUNT - 1);
        double x = ORIGIN_X + col * COLUMN_SPACING;
        double maxY = ORIGIN_Y - ROW_SPACING;
        for (var pos : existing.positions().values()) {
            if (Math.abs(pos.x() - x) < COLUMN_SPACING / 2.0 && pos.y() > maxY) {
                maxY = pos.y();
            }
        }
        return new ComponentPosition(component.id(), x, maxY + ROW_SPACING, 0);
    }

    public static int columnFor(CircuitComponent component) {
        return ComponentPresenters.of(component).autoLayoutColumn();
    }
}
