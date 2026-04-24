package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.ui.workbench.pane.schematic.ComponentGeometry;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.VBox;

import java.util.List;

/** Shunt resistor — one wireable terminal, other side implicit ground. */
final class ShuntResistorPresenter implements ComponentPresenter {
    private static final ComponentGeometry GEOM = new ComponentGeometry(90, 60, List.of(
        new ComponentGeometry.Terminal("in", -45, 0)
    ));

    private final CircuitComponent.ShuntResistor r;

    ShuntResistorPresenter(CircuitComponent.ShuntResistor r) { this.r = r; }

    @Override public ComponentGeometry geometry() { return GEOM; }

    @Override
    public void drawBody(GraphicsContext g) {
        SchematicInk.drawShuntBody(g, r.name(), "R");
    }

    @Override
    public void buildInspector(VBox container, InspectorEnv env) {
        container.getChildren().add(InspectorFields.doubleField(
            "Shunt R (ohms)", r.resistanceOhms(),
            v -> env.session().replaceComponent(r.withResistanceOhms(v))));
    }

    @Override public int autoLayoutColumn() { return 2; }
    @Override public String displayName() { return "Resistor (parallel)"; }
}
