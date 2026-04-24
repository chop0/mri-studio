package ax.xz.mri.ui.workbench.pane.schematic;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.ComponentPosition;
import ax.xz.mri.ui.workbench.pane.schematic.presenter.ComponentPresenters;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Stateless renderer that draws circuit components on a JavaFX canvas.
 *
 * <p>Owns only the shell — transforms, halo, hover ring, and terminal dots —
 * and delegates the body drawing to the per-kind presenter. The presenters
 * live under
 * {@link ax.xz.mri.ui.workbench.pane.schematic.presenter} and are the single
 * place each component's draw code lives.
 */
public final class ComponentRenderer {
    private static final Color SELECTION = Color.web("#1976d2");
    private static final Color HOVER = Color.web("#94a3b8");
    private static final Color TERMINAL = Color.web("#475569");

    private ComponentRenderer() {}

    public static void draw(GraphicsContext g, CircuitComponent component, ComponentPosition pos,
                            boolean selected, boolean hovered) {
        g.save();
        g.translate(pos.x(), pos.y());
        if (pos.rotationQuarters() != 0) g.rotate(pos.rotationQuarters() * 90.0);
        if (pos.mirrored()) g.scale(-1, 1);

        var presenter = ComponentPresenters.of(component);
        var geom = presenter.geometry();
        if (selected) drawSelection(g, geom);
        else if (hovered) drawHover(g, geom);

        presenter.drawBody(g);
        drawTerminals(g, geom);

        g.restore();
    }

    private static void drawSelection(GraphicsContext g, ComponentGeometry geom) {
        g.setFill(SELECTION.deriveColor(0, 1, 1, 0.14));
        g.fillRoundRect(-geom.halfWidth() - 6, -geom.halfHeight() - 6,
            geom.width() + 12, geom.height() + 12, 8, 8);
        g.setStroke(SELECTION);
        g.setLineWidth(1.4);
        g.strokeRoundRect(-geom.halfWidth() - 6, -geom.halfHeight() - 6,
            geom.width() + 12, geom.height() + 12, 8, 8);
    }

    private static void drawHover(GraphicsContext g, ComponentGeometry geom) {
        g.setStroke(HOVER);
        g.setLineDashes(3, 3);
        g.setLineWidth(1);
        g.strokeRoundRect(-geom.halfWidth() - 4, -geom.halfHeight() - 4,
            geom.width() + 8, geom.height() + 8, 6, 6);
        g.setLineDashes();
    }

    private static void drawTerminals(GraphicsContext g, ComponentGeometry geom) {
        g.setFill(TERMINAL);
        for (var t : geom.terminals()) {
            g.fillOval(t.xOffset() - 3.5, t.yOffset() - 3.5, 7, 7);
            g.setStroke(Color.WHITE);
            g.setLineWidth(1);
            g.strokeOval(t.xOffset() - 3.5, t.yOffset() - 3.5, 7, 7);
        }
    }
}
