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

/**
 * Voltage-metadata tap — wires its {@code source} input to a source's
 * {@code out}, then emits a 0/1 "is a clip playing" signal on {@code out}
 * that downstream switches / muxes / probes read. Drawn as a small boxed
 * dashed-gate glyph to distinguish it from load-bearing components.
 */
final class VoltageMetadataPresenter implements ComponentPresenter {
    private static final ComponentGeometry GEOM = new ComponentGeometry(90, 60, List.of(
        new ComponentGeometry.Terminal("source", -45, 0),
        new ComponentGeometry.Terminal("out", 45, 0)
    ));

    private final CircuitComponent.VoltageMetadata m;

    VoltageMetadataPresenter(CircuitComponent.VoltageMetadata m) { this.m = m; }

    @Override public ComponentGeometry geometry() { return GEOM; }

    @Override
    public void drawBody(GraphicsContext g) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-45, 0, -14, 0);
        g.strokeLine(14, 0, 45, 0);
        g.setFill(Color.WHITE);
        g.fillRoundRect(-14, -10, 28, 20, 4, 4);
        g.setStroke(GATE_ACCENT);
        g.setLineWidth(1.5);
        g.strokeRoundRect(-14, -10, 28, 20, 4, 4);
        g.setFill(GATE_ACCENT);
        g.setFont(Font.font("System", 9));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(modeGlyph(m.mode()), 0, 3);
        drawLabel(g, m.name(), INK, 0, 24);
    }

    private static String modeGlyph(CircuitComponent.VoltageMetadata.Mode mode) {
        return switch (mode) {
            case ACTIVE -> "ACT";
        };
    }

    @Override
    public void buildInspector(VBox container, InspectorEnv env) {
        var help = new Label(
            "Wire the source-input to a source's 'out' to observe it. " +
            "The output emits a 1 while that source has any active control " +
            "channel, 0 otherwise — use it to gate switches or muxes.");
        help.setWrapText(true);
        help.getStyleClass().add("schematic-inspector-hint");
        container.getChildren().add(help);
        container.getChildren().add(InspectorFields.enumField(
            "Mode", CircuitComponent.VoltageMetadata.Mode.values(), m.mode(),
            mode -> env.session().replaceComponent(m.withMode(mode))));
    }

    @Override public int autoLayoutColumn() { return 1; }
    @Override public String displayName() { return "Voltage metadata"; }
}
