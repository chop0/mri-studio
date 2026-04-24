package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.ui.workbench.pane.schematic.ComponentGeometry;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.VBox;
import javafx.scene.shape.ArcType;

import java.util.List;

import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.INK;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.drawLabel;

/** Ideal two-port transformer — primary and secondary windings facing each other. */
final class IdealTransformerPresenter implements ComponentPresenter {
    private static final ComponentGeometry GEOM = new ComponentGeometry(100, 80, List.of(
        new ComponentGeometry.Terminal("pa", -50, -20),
        new ComponentGeometry.Terminal("pb", -50, 20),
        new ComponentGeometry.Terminal("sa", 50, -20),
        new ComponentGeometry.Terminal("sb", 50, 20)
    ));

    private final CircuitComponent.IdealTransformer t;

    IdealTransformerPresenter(CircuitComponent.IdealTransformer t) { this.t = t; }

    @Override public ComponentGeometry geometry() { return GEOM; }

    @Override
    public void drawBody(GraphicsContext g) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-50, -20, -26, -20);
        g.strokeLine(-50, 20, -26, 20);
        g.strokeLine(26, -20, 50, -20);
        g.strokeLine(26, 20, 50, 20);
        for (int i = 0; i < 3; i++) {
            double cy = -14 + i * 14;
            g.strokeArc(-28, cy - 6, 12, 12, 90, 180, ArcType.OPEN);
            g.strokeArc(16, cy - 6, 12, 12, -90, 180, ArcType.OPEN);
        }
        drawLabel(g, t.name(), INK, 0, 42);
    }

    @Override
    public void buildInspector(VBox container, InspectorEnv env) {
        container.getChildren().add(InspectorFields.doubleField(
            "Turns ratio", t.turnsRatio(),
            v -> env.session().replaceComponent(t.withTurnsRatio(v))));
    }

    @Override public int autoLayoutColumn() { return 2; }
    @Override public String displayName() { return "Ideal transformer"; }
}
