package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.ui.workbench.pane.schematic.ComponentGeometry;
import javafx.geometry.Pos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.List;

import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.INK;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.PROBE_ACCENT;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.drawLabel;

/** Voltage probe — single-terminal observer with a named signal trace. */
final class ProbePresenter implements ComponentPresenter {
    private static final ComponentGeometry GEOM = new ComponentGeometry(90, 60, List.of(
        new ComponentGeometry.Terminal("in", -45, 0)
    ));

    private final CircuitComponent.Probe p;

    ProbePresenter(CircuitComponent.Probe p) { this.p = p; }

    @Override public ComponentGeometry geometry() { return GEOM; }

    @Override
    public void drawBody(GraphicsContext g) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-45, 0, -18, 0);
        g.setFill(Color.WHITE);
        g.fillRoundRect(-18, -16, 36, 32, 4, 4);
        g.setStroke(PROBE_ACCENT);
        g.setLineWidth(1.6);
        g.strokeRoundRect(-18, -16, 36, 32, 4, 4);
        g.setFill(PROBE_ACCENT);
        g.fillPolygon(new double[]{-8, 8, 0}, new double[]{-7, -7, 7}, 3);
        drawLabel(g, p.name(), INK, 0, 28);
    }

    @Override
    public void buildInspector(VBox container, InspectorEnv env) {
        var help = new Label(
            "Emits a complex signal named after this probe, observed through the coil it's wired to.");
        help.setWrapText(true);
        help.getStyleClass().add("schematic-inspector-hint");
        container.getChildren().add(help);

        var traceRow = new HBox(6);
        traceRow.setAlignment(Pos.CENTER_LEFT);
        var traceLabel = new Label("Signal trace");
        traceLabel.setPrefWidth(100);
        var traceName = new Label(p.name());
        traceName.getStyleClass().add("schematic-inspector-value");
        traceRow.getChildren().addAll(traceLabel, traceName);
        container.getChildren().add(traceRow);

        container.getChildren().add(InspectorFields.doubleField("Gain", p.gain(),
            v -> env.session().replaceComponent(p.withGain(v))));
        container.getChildren().add(InspectorFields.doubleField("Phase (deg)", p.demodPhaseDeg(),
            v -> env.session().replaceComponent(p.withDemodPhaseDeg(v))));
        container.getChildren().add(InspectorFields.doubleField("Load (ohms)", p.loadImpedanceOhms(),
            v -> env.session().replaceComponent(p.withLoadImpedanceOhms(v))));
    }

    @Override public int autoLayoutColumn() { return 3; }
    @Override public String displayName() { return "Probe"; }
}
