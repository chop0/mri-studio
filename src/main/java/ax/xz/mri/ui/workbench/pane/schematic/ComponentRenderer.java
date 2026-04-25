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
 *
 * <p>Three optional decorations stack on top of the body:
 * <ul>
 *   <li>{@code highlighted} — amber halo, drawn underneath everything else.
 *       Used by the inspector's "Show in schematic" affordance to light up
 *       the path between a clip's source and the coil it eventually drives.</li>
 *   <li>{@code selected} — blue halo. Standard editor selection.</li>
 *   <li>{@code hovered} — dashed grey ring. Drawn only when nothing else is
 *       active on the component.</li>
 * </ul>
 */
public final class ComponentRenderer {
    private static final Color SELECTION = Color.web("#1976d2");
    private static final Color HOVER = Color.web("#94a3b8");
    private static final Color TERMINAL = Color.web("#475569");
    /** Amber halo for "this is on the path you asked to highlight". */
    private static final Color HIGHLIGHT = Color.web("#f59e0b");

    private ComponentRenderer() {}

    public static void draw(GraphicsContext g, CircuitComponent component, ComponentPosition pos,
                            boolean selected, boolean hovered) {
        draw(g, component, pos, selected, hovered, false);
    }

    public static void draw(GraphicsContext g, CircuitComponent component, ComponentPosition pos,
                            boolean selected, boolean hovered, boolean highlighted) {
        g.save();
        g.translate(pos.x(), pos.y());
        if (pos.rotationQuarters() != 0) g.rotate(pos.rotationQuarters() * 90.0);
        if (pos.mirrored()) g.scale(-1, 1);

        var presenter = ComponentPresenters.of(component);
        var geom = presenter.geometry();
        if (highlighted) drawHighlight(g, geom);
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

    private static void drawHighlight(GraphicsContext g, ComponentGeometry geom) {
        // Wider, softer halo than selection so the two coexist without clashing.
        g.setFill(HIGHLIGHT.deriveColor(0, 1, 1, 0.22));
        g.fillRoundRect(-geom.halfWidth() - 9, -geom.halfHeight() - 9,
            geom.width() + 18, geom.height() + 18, 10, 10);
        g.setStroke(HIGHLIGHT);
        g.setLineWidth(2.0);
        g.strokeRoundRect(-geom.halfWidth() - 9, -geom.halfHeight() - 9,
            geom.width() + 18, geom.height() + 18, 10, 10);
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
