package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.ui.workbench.pane.schematic.ComponentGeometry;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.VBox;

import java.util.List;

import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.INK;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.drawLabel;

/** Series capacitor — two parallel plates. */
final class CapacitorPresenter implements ComponentPresenter {
    private static final ComponentGeometry GEOM = new ComponentGeometry(90, 60, List.of(
        new ComponentGeometry.Terminal("a", -45, 0),
        new ComponentGeometry.Terminal("b", 45, 0)
    ));

    private final CircuitComponent.Capacitor c;

    CapacitorPresenter(CircuitComponent.Capacitor c) { this.c = c; }

    @Override public ComponentGeometry geometry() { return GEOM; }

    @Override
    public void drawBody(GraphicsContext g) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-45, 0, -6, 0);
        g.strokeLine(6, 0, 45, 0);
        g.strokeLine(-6, -14, -6, 14);
        g.strokeLine(6, -14, 6, 14);
        drawLabel(g, c.name(), INK, 0, 24);
    }

    @Override
    public void buildInspector(VBox container, InspectorEnv env) {
        container.getChildren().add(InspectorFields.doubleField(
            "Capacitance (F)", c.capacitanceFarads(),
            v -> env.session().replaceComponent(c.withCapacitanceFarads(v))));
    }

    @Override public int autoLayoutColumn() { return 1; }
    @Override public String displayName() { return "Capacitor (series)"; }
}
