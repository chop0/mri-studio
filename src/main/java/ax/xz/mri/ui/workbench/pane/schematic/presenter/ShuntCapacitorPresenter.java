package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.ui.workbench.pane.schematic.ComponentGeometry;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.VBox;

import java.util.List;

/** Shunt capacitor — one wireable terminal, other side implicit ground. */
final class ShuntCapacitorPresenter implements ComponentPresenter {
    private static final ComponentGeometry GEOM = new ComponentGeometry(90, 60, List.of(
        new ComponentGeometry.Terminal("in", -45, 0)
    ));

    private final CircuitComponent.ShuntCapacitor c;

    ShuntCapacitorPresenter(CircuitComponent.ShuntCapacitor c) { this.c = c; }

    @Override public ComponentGeometry geometry() { return GEOM; }

    @Override
    public void drawBody(GraphicsContext g) {
        SchematicInk.drawShuntBody(g, c.name(), "C");
    }

    @Override
    public void buildInspector(VBox container, InspectorEnv env) {
        container.getChildren().add(InspectorFields.doubleField(
            "Shunt C (F)", c.capacitanceFarads(),
            v -> env.session().replaceComponent(c.withCapacitanceFarads(v))));
    }

    @Override public int autoLayoutColumn() { return 2; }
    @Override public String displayName() { return "Capacitor (parallel)"; }
}
