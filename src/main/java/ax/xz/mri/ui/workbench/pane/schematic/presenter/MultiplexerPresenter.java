package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.ui.workbench.pane.schematic.ComponentGeometry;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.List;

import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.GATE_ACCENT;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.INK;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.drawLabel;

/** SPDT routing element: {@code common} ↔ {@code a} when ctl high, ↔ {@code b} when low. */
final class MultiplexerPresenter implements ComponentPresenter {
    private static final ComponentGeometry GEOM = new ComponentGeometry(110, 90, List.of(
        new ComponentGeometry.Terminal("a", -55, -20),
        new ComponentGeometry.Terminal("b", -55, 20),
        new ComponentGeometry.Terminal("common", 55, 0),
        new ComponentGeometry.Terminal("ctl", 0, 45)
    ));

    private final CircuitComponent.Multiplexer m;

    MultiplexerPresenter(CircuitComponent.Multiplexer m) { this.m = m; }

    @Override public ComponentGeometry geometry() { return GEOM; }

    @Override
    public void drawBody(GraphicsContext g) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-55, -20, -40, -20);
        g.strokeLine(-55, 20, -40, 20);
        g.strokeLine(40, 0, 55, 0);
        g.setFill(Color.WHITE);
        double[] bx = {-40, 40, 40, -40};
        double[] by = {-28, -14, 14, 28};
        g.fillPolygon(bx, by, 4);
        g.setStroke(GATE_ACCENT);
        g.setLineWidth(1.6);
        g.strokePolygon(bx, by, 4);
        g.setFill(INK);
        g.setFont(Font.font("System", 9));
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText("a", -36, -17);
        g.fillText("b", -36, 23);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText("c", 36, 3);
        g.setStroke(GATE_ACCENT.deriveColor(0, 1, 1, 0.7));
        g.setLineDashes(3, 2);
        g.strokeLine(0, 24, 0, 45);
        g.setLineDashes();
        drawLabel(g, m.name(), INK, 0, -34);
    }

    @Override
    public void buildInspector(VBox container, InspectorEnv env) {
        var help = new Label(
            "SPDT routing: common connects to 'a' when ctl is high, " +
            "to 'b' when ctl is low. Wire ctl to a source's 'active' port " +
            "for automatic T/R switching.");
        help.setWrapText(true);
        help.getStyleClass().add("schematic-inspector-hint");
        container.getChildren().add(help);
        container.getChildren().add(InspectorFields.doubleField("Closed (ohms)", m.closedOhms(),
            v -> env.session().replaceComponent(new CircuitComponent.Multiplexer(
                m.id(), m.name(), v, m.openOhms(), m.thresholdVolts()))));
        container.getChildren().add(InspectorFields.doubleField("Open (ohms)", m.openOhms(),
            v -> env.session().replaceComponent(new CircuitComponent.Multiplexer(
                m.id(), m.name(), m.closedOhms(), v, m.thresholdVolts()))));
        container.getChildren().add(InspectorFields.doubleField("Threshold V", m.thresholdVolts(),
            v -> env.session().replaceComponent(new CircuitComponent.Multiplexer(
                m.id(), m.name(), m.closedOhms(), m.openOhms(), v))));
    }

    @Override public int autoLayoutColumn() { return 1; }
    @Override public String displayName() { return "Multiplexer"; }
}
