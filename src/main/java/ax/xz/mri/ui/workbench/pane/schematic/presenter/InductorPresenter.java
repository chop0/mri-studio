package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.ui.workbench.pane.schematic.ComponentGeometry;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.VBox;
import javafx.scene.shape.ArcType;

import java.util.List;

import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.INK;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.drawLabel;

/** Series inductor — four loop arcs. */
final class InductorPresenter implements ComponentPresenter {
    private static final ComponentGeometry GEOM = new ComponentGeometry(90, 60, List.of(
        new ComponentGeometry.Terminal("a", -45, 0),
        new ComponentGeometry.Terminal("b", 45, 0)
    ));

    private final CircuitComponent.Inductor l;

    InductorPresenter(CircuitComponent.Inductor l) { this.l = l; }

    @Override public ComponentGeometry geometry() { return GEOM; }

    @Override
    public void drawBody(GraphicsContext g) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-45, 0, -26, 0);
        g.strokeLine(26, 0, 45, 0);
        for (int i = 0; i < 4; i++) {
            double cx = -18 + i * 12;
            g.strokeArc(cx - 6, -6, 12, 12, 0, 180, ArcType.OPEN);
        }
        drawLabel(g, l.name(), INK, 0, 20);
    }

    @Override
    public void buildInspector(VBox container, InspectorEnv env) {
        container.getChildren().add(InspectorFields.doubleField(
            "Inductance (H)", l.inductanceHenry(),
            v -> env.session().replaceComponent(l.withInductanceHenry(v))));
    }

    @Override public int autoLayoutColumn() { return 1; }
    @Override public String displayName() { return "Inductor (series)"; }
}
