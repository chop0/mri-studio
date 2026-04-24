package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.ui.workbench.pane.schematic.ComponentGeometry;
import javafx.geometry.Pos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;

import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.GATE_ACCENT;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.INK;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.drawLabel;

/** Gated two-terminal switch with a ctl input. */
final class SwitchPresenter implements ComponentPresenter {
    private static final ComponentGeometry GEOM = new ComponentGeometry(90, 70, List.of(
        new ComponentGeometry.Terminal("a", -45, 0),
        new ComponentGeometry.Terminal("b", 45, 0),
        new ComponentGeometry.Terminal("ctl", 0, 35)
    ));

    private final CircuitComponent.SwitchComponent s;

    SwitchPresenter(CircuitComponent.SwitchComponent s) { this.s = s; }

    @Override public ComponentGeometry geometry() { return GEOM; }

    @Override
    public void drawBody(GraphicsContext g) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-45, 0, -18, 0);
        g.strokeLine(18, 0, 45, 0);
        g.setStroke(GATE_ACCENT);
        g.setLineWidth(2);
        g.strokeLine(-18, 0, 14, -14);
        g.setFill(INK);
        g.fillOval(-20, -2, 4, 4);
        g.fillOval(16, -2, 4, 4);
        g.setStroke(GATE_ACCENT.deriveColor(0, 1, 1, 0.7));
        g.setLineDashes(3, 2);
        g.strokeLine(0, 14, 0, 35);
        g.setLineDashes();
        drawLabel(g, s.name(), INK, 0, -22);
    }

    @Override
    public void buildInspector(VBox container, InspectorEnv env) {
        container.getChildren().add(InspectorFields.doubleField("Closed (ohms)", s.closedOhms(),
            v -> env.session().replaceComponent(new CircuitComponent.SwitchComponent(
                s.id(), s.name(), v, s.openOhms(), s.thresholdVolts(), s.invertCtl()))));
        container.getChildren().add(InspectorFields.doubleField("Open (ohms)", s.openOhms(),
            v -> env.session().replaceComponent(new CircuitComponent.SwitchComponent(
                s.id(), s.name(), s.closedOhms(), v, s.thresholdVolts(), s.invertCtl()))));
        container.getChildren().add(InspectorFields.doubleField("Threshold V", s.thresholdVolts(),
            v -> env.session().replaceComponent(new CircuitComponent.SwitchComponent(
                s.id(), s.name(), s.closedOhms(), s.openOhms(), v, s.invertCtl()))));

        var invert = new CheckBox("Invert ctl (close when ctl is low)");
        invert.setSelected(s.invertCtl());
        invert.setOnAction(e -> env.session().replaceComponent(s.withInvertCtl(invert.isSelected())));
        var row = new HBox(6, new Label(""));
        ((Label) row.getChildren().get(0)).setPrefWidth(100);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().add(invert);
        container.getChildren().add(row);
    }

    @Override public int autoLayoutColumn() { return 1; }
    @Override public String displayName() { return "Switch"; }
}
