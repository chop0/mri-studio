package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.ui.workbench.pane.schematic.ComponentGeometry;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.VBox;

import java.util.List;

import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.INK;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.drawLabel;

/** Series resistor — two terminals, zigzag body. */
final class ResistorPresenter implements ComponentPresenter {
    private static final ComponentGeometry GEOM = new ComponentGeometry(90, 60, List.of(
        new ComponentGeometry.Terminal("a", -45, 0),
        new ComponentGeometry.Terminal("b", 45, 0)
    ));

    private final CircuitComponent.Resistor r;

    ResistorPresenter(CircuitComponent.Resistor r) { this.r = r; }

    @Override public ComponentGeometry geometry() { return GEOM; }

    @Override
    public void drawBody(GraphicsContext g) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-45, 0, -26, 0);
        g.strokeLine(26, 0, 45, 0);
        double[] xs = {-26, -20, -12, -4, 4, 12, 20, 26};
        double[] ys = {0, -8, 8, -8, 8, -8, 8, 0};
        g.strokePolyline(xs, ys, xs.length);
        drawLabel(g, r.name(), INK, 0, 20);
    }

    @Override
    public void buildInspector(VBox container, InspectorEnv env) {
        container.getChildren().add(InspectorFields.doubleField(
            "Resistance (ohms)", r.resistanceOhms(),
            v -> env.session().replaceComponent(r.withResistanceOhms(v))));
    }

    @Override public int autoLayoutColumn() { return 1; }
    @Override public String displayName() { return "Resistor (series)"; }
}
