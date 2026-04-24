package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.ui.workbench.pane.schematic.ComponentGeometry;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.List;

import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.INK;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.MIXER_ACCENT;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.drawLabel;

/**
 * I/Q Mixer — the standard mixer circle-with-×, with a dashed LO
 * tap from below. Pure observer on the schematic — its output is the input's
 * complex signal rotated by {@code exp(-i·2π·loHz·t)}.
 */
final class MixerPresenter implements ComponentPresenter {
    private static final ComponentGeometry GEOM = new ComponentGeometry(90, 70, List.of(
        new ComponentGeometry.Terminal("in", -45, 0),
        new ComponentGeometry.Terminal("out", 45, 0)
    ));

    private final CircuitComponent.Mixer dc;

    MixerPresenter(CircuitComponent.Mixer dc) { this.dc = dc; }

    @Override public ComponentGeometry geometry() { return GEOM; }

    @Override
    public void drawBody(GraphicsContext g) {
        // Leads.
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-45, 0, -22, 0);
        g.strokeLine(22, 0, 45, 0);
        // Body.
        g.setFill(Color.WHITE);
        g.fillOval(-22, -22, 44, 44);
        g.setStroke(MIXER_ACCENT);
        g.setLineWidth(1.6);
        g.strokeOval(-22, -22, 44, 44);
        // × inside the mixer.
        g.strokeLine(-11, -11, 11, 11);
        g.strokeLine(11, -11, -11, 11);
        // LO tick at the bottom (dashed) — schematic cue that this block
        // has a local-oscillator input, even though it's parameter-only.
        g.setStroke(MIXER_ACCENT.deriveColor(0, 1, 1, 0.6));
        g.setLineDashes(3, 2);
        g.strokeLine(0, 22, 0, 32);
        g.setLineDashes();
        // Name on top, LO frequency underneath.
        drawLabel(g, dc.name(), INK, 0, -30);
        drawLabel(g, formatLo(dc.loHz()), MIXER_ACCENT, 0, 40);
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
            "Sits in series between the circuit and a probe. Rotates the " +
            "tapped voltage by exp(-j·2\u03C0\u00b7loHz\u00b7t); the probe downstream " +
            "reads I as the real channel and Q as the imaginary channel of its " +
            "complex trace. Set loHz to your carrier (e.g. Larmor) to see baseband.");
        help.setWrapText(true);
        help.getStyleClass().add("schematic-inspector-hint");
        container.getChildren().add(help);
        container.getChildren().add(InspectorFields.doubleField("LO (Hz)", dc.loHz(),
            v -> env.session().replaceComponent(dc.withLoHz(v))));
    }

    @Override public int autoLayoutColumn() { return 3; }
    @Override public String displayName() { return "Mixer"; }
}
