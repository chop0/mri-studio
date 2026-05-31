package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import module ax.xz.mri;
import module javafx.controls;
import module javafx.graphics;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.ui.workbench.pane.schematic.ComponentGeometry;

import java.util.List;

import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.INK;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.drawLabel;

/**
 * Schematic glyph for a {@link CircuitComponent.OpticalCounter} — a
 * photon-counter probe wired from a {@link CircuitComponent.Substance}
 * block's {@code clicks_red} (or future {@code clicks_green}) port.
 * Drawn as a round aperture-style icon with the optical accent.
 */
final class OpticalCounterPresenter implements ComponentPresenter {

    private static final Color OPTICAL_ACCENT = Color.web("#b3531a");
    private static final Color BODY_FILL      = Color.web("#fbece1");

    private static final ComponentGeometry GEOM = new ComponentGeometry(70, 60, List.of(
        new ComponentGeometry.Terminal("in", -35, 0)
    ));

    private final CircuitComponent.OpticalCounter c;

    OpticalCounterPresenter(CircuitComponent.OpticalCounter c) { this.c = c; }

    @Override public ComponentGeometry geometry() { return GEOM; }

    @Override
    public void drawBody(GraphicsContext g) {
        // Aperture-style circle with concentric ring.
        g.setFill(BODY_FILL);
        g.fillOval(-16, -16, 32, 32);
        g.setStroke(OPTICAL_ACCENT);
        g.setLineWidth(1.6);
        g.strokeOval(-16, -16, 32, 32);
        g.setLineWidth(1.0);
        g.strokeOval(-10, -10, 20, 20);
        // Tiny pinhole at centre.
        g.setFill(OPTICAL_ACCENT);
        g.fillOval(-2, -2, 4, 4);
        // Input lead.
        g.setStroke(OPTICAL_ACCENT);
        g.setLineWidth(1.4);
        g.strokeLine(-35, 0, -16, 0);

        // Accent kicker.
        g.setFill(OPTICAL_ACCENT);
        g.setFont(Font.font("System", 7));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText("γ-counter", 0, -22);

        drawLabel(g, c.name(), INK, 0, 24);
    }

    @Override
    public void buildInspector(VBox container, InspectorEnv env) {
        container.getChildren().add(InspectorFields.doubleField(
            "Quantum efficiency", c.quantumEfficiency(),
            v -> env.session().replaceComponent(c.withQuantumEfficiency(clamp01(v)))));
        container.getChildren().add(InspectorFields.doubleField(
            "Dark rate (Hz)", c.darkRateHz(),
            v -> env.session().replaceComponent(c.withDarkRateHz(Math.max(0, v)))));
        container.getChildren().add(InspectorFields.doubleField(
            "Seed", (double) c.seed(),
            v -> env.session().replaceComponent(c.withSeed((long) v.doubleValue()))));
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    @Override public int autoLayoutColumn() { return 4; }
    @Override public String displayName() { return "Optical counter"; }
}
