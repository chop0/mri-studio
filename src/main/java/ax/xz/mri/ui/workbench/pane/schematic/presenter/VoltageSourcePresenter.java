package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.ui.workbench.pane.schematic.ComponentGeometry;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.List;

import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.ACCENT;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.GATE_ACCENT;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.INK;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.drawLabel;

/** Voltage source — the schematic-side endpoint of a DAW track. */
final class VoltageSourcePresenter implements ComponentPresenter {
    private static final ComponentGeometry GEOM = new ComponentGeometry(90, 70, List.of(
        new ComponentGeometry.Terminal("out", 45, 0),
        new ComponentGeometry.Terminal("active", 0, 35)
    ));

    private final CircuitComponent.VoltageSource v;

    VoltageSourcePresenter(CircuitComponent.VoltageSource v) { this.v = v; }

    @Override public ComponentGeometry geometry() { return GEOM; }

    @Override
    public void drawBody(GraphicsContext g) {
        Color accent = switch (v.kind()) {
            case GATE -> GATE_ACCENT;
            case STATIC -> Color.web("#475569");
            default -> ACCENT;
        };
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(22, 0, 45, 0);
        g.setStroke(accent.deriveColor(0, 1, 1, 0.6));
        g.setLineDashes(3, 2);
        g.strokeLine(0, 22, 0, 35);
        g.setLineDashes();
        g.setFill(Color.WHITE);
        g.fillOval(-22, -22, 44, 44);
        g.setStroke(accent);
        g.setLineWidth(1.6);
        g.strokeOval(-22, -22, 44, 44);
        g.setFill(accent);
        g.setFont(Font.font("System", 11));
        g.setTextAlign(TextAlignment.CENTER);
        String glyph = switch (v.kind()) {
            case GATE -> "G";
            case REAL -> "V";
            case STATIC -> "DC";
        };
        g.fillText(glyph, 0, 4);
        drawLabel(g, v.name(), INK, 0, -30);
    }

    @Override
    public void buildInspector(VBox container, InspectorEnv env) {
        var help = new Label(
            "The source's name doubles as the DAW track name \u2014 every track " +
            "with this name sums into the source each step, then its voltage drives " +
            "the coil it's wired to.");
        help.setWrapText(true);
        help.getStyleClass().add("schematic-inspector-hint");
        container.getChildren().add(help);
        container.getChildren().add(InspectorFields.enumField("Kind", AmplitudeKind.values(), v.kind(),
            k -> env.session().replaceComponent(v.withKind(k))));
        container.getChildren().add(InspectorFields.doubleField("Carrier (Hz)", v.carrierHz(),
            d -> env.session().replaceComponent(v.withCarrierHz(d))));
        container.getChildren().add(InspectorFields.doubleField("Min amplitude", v.minAmplitude(),
            d -> env.session().replaceComponent(v.withMinAmplitude(d))));
        container.getChildren().add(InspectorFields.doubleField("Max amplitude", v.maxAmplitude(),
            d -> env.session().replaceComponent(v.withMaxAmplitude(d))));
        container.getChildren().add(InspectorFields.doubleField("Output R (ohms)", v.outputImpedanceOhms(),
            d -> env.session().replaceComponent(v.withOutputImpedanceOhms(d))));
    }

    @Override public int autoLayoutColumn() { return 0; }
    @Override public String displayName() { return "Voltage source"; }
}
