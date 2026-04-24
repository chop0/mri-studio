package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.compile.ComplexPairFormat;
import ax.xz.mri.ui.workbench.pane.schematic.ComponentGeometry;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.List;

import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.INK;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.MIXER_ACCENT;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.drawLabel;

/**
 * Quadrature up-mixer. Two scalar inputs on the left (I/Q or mag/phase
 * per {@link ComplexPairFormat}) combine into a single complex output
 * on the right, upconverted to {@code loHz}. Mirror of
 * {@link MixerPresenter}.
 */
final class ModulatorPresenter implements ComponentPresenter {
    private static final ComponentGeometry GEOM = new ComponentGeometry(100, 90, List.of(
        new ComponentGeometry.Terminal("in0", -50, -20),
        new ComponentGeometry.Terminal("in1", -50, 20),
        new ComponentGeometry.Terminal("out", 50, 0)
    ));

    private final CircuitComponent.Modulator m;

    ModulatorPresenter(CircuitComponent.Modulator m) { this.m = m; }

    @Override public ComponentGeometry geometry() { return GEOM; }

    @Override
    public void drawBody(GraphicsContext g) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-50, -20, -22, -8);
        g.strokeLine(-50, 20, -22, 8);
        g.strokeLine(22, 0, 50, 0);
        g.setFill(Color.WHITE);
        g.fillOval(-22, -22, 44, 44);
        g.setStroke(MIXER_ACCENT);
        g.setLineWidth(1.6);
        g.strokeOval(-22, -22, 44, 44);
        g.strokeLine(-11, -11, 11, 11);
        g.strokeLine(11, -11, -11, 11);
        // LO tick on TOP (vs bottom on the Mixer) to convey "carrier
        // being added in" rather than "carrier being stripped out".
        g.setStroke(MIXER_ACCENT.deriveColor(0, 1, 1, 0.6));
        g.setLineDashes(3, 2);
        g.strokeLine(0, -22, 0, -34);
        g.setLineDashes();
        // Input labels inside the block — symmetric mirror of Mixer's
        // output labels.
        g.setFill(MIXER_ACCENT);
        g.setFont(Font.font("System", 9));
        g.setTextAlign(TextAlignment.RIGHT);
        String[] inLabels = labelsFor(m.format());
        g.fillText(inLabels[0], -28, -17);
        g.fillText(inLabels[1], -28, 25);
        drawLabel(g, m.name(), INK, 0, -42);
        drawLabel(g, formatLo(m.loHz()), MIXER_ACCENT, 0, 36);
    }

    private static String[] labelsFor(ComplexPairFormat format) {
        return switch (format) {
            case IQ -> new String[]{"I", "Q"};
            case MAG_PHASE -> new String[]{"|\u00b7|", "\u2220"};
        };
    }

    private static String formatLo(double hz) {
        if (hz == 0) return "LO: DC";
        double abs = Math.abs(hz);
        if (abs >= 1e6) return String.format("LO: %.3g MHz", hz / 1e6);
        if (abs >= 1e3) return String.format("LO: %.3g kHz", hz / 1e3);
        return String.format("LO: %.3g Hz", hz);
    }

    @Override
    public void buildInspector(VBox container, InspectorEnv env) {
        var help = new Label(
            "Combines two scalar inputs into a complex envelope and upconverts " +
            "to loHz. Wire two sources (or arbitrary node voltages) to in0/in1. " +
            "Pick I/Q for rectangular or Mag/Phase for polar.");
        help.setWrapText(true);
        help.getStyleClass().add("schematic-inspector-hint");
        container.getChildren().add(help);
        container.getChildren().add(InspectorFields.enumField("Format",
            ComplexPairFormat.values(), m.format(),
            f -> env.session().replaceComponent(m.withFormat(f))));
        container.getChildren().add(InspectorFields.doubleField("LO (Hz)", m.loHz(),
            v -> env.session().replaceComponent(m.withLoHz(v))));
    }

    @Override public int autoLayoutColumn() { return 1; }
    @Override public String displayName() { return "Modulator (upconverter)"; }
}
