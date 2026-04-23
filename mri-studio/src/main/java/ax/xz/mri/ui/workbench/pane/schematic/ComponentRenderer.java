package ax.xz.mri.ui.workbench.pane.schematic;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.simulation.AmplitudeKind;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * Stateless renderer that draws circuit components on a JavaFX canvas.
 *
 * <p>Every component is drawn centered at a given world position using only
 * primitive shapes (no image assets). The colour palette is tuned for a
 * light-background schematic; selected components get a halo, hovered
 * components a subtle ring.
 */
public final class ComponentRenderer {
    private static final Color INK = Color.web("#1f2933");
    private static final Color ACCENT = Color.web("#1976d2");
    private static final Color GATE_ACCENT = Color.web("#d97706");
    private static final Color PROBE_ACCENT = Color.web("#059669");
    private static final Color COIL_ACCENT = Color.web("#7c3aed");
    private static final Color SELECTION = Color.web("#1976d2");
    private static final Color HOVER = Color.web("#94a3b8");
    private static final Color TERMINAL = Color.web("#475569");

    private ComponentRenderer() {}

    public static void draw(GraphicsContext g, CircuitComponent component, double x, double y,
                            boolean selected, boolean hovered) {
        g.save();
        g.translate(x, y);

        var geom = ComponentGeometry.of(component);
        if (selected) drawSelection(g, geom);
        else if (hovered) drawHover(g, geom);

        switch (component) {
            case CircuitComponent.VoltageSource v -> drawVoltageSource(g, v);
            case CircuitComponent.SwitchComponent s -> drawSwitch(g, s);
            case CircuitComponent.Coil c -> drawCoil(g, c);
            case CircuitComponent.Probe p -> drawProbe(g, p);
            case CircuitComponent.Ground ignored -> drawGround(g);
            case CircuitComponent.Resistor r -> drawResistor(g, r);
            case CircuitComponent.Capacitor c -> drawCapacitor(g, c);
            case CircuitComponent.Inductor l -> drawInductor(g, l);
            case CircuitComponent.IdealTransformer t -> drawTransformer(g, t);
        }

        drawTerminals(g, geom);

        g.restore();
    }

    private static void drawSelection(GraphicsContext g, ComponentGeometry geom) {
        g.setFill(SELECTION.deriveColor(0, 1, 1, 0.14));
        g.fillRoundRect(-geom.halfWidth() - 6, -geom.halfHeight() - 6,
            geom.width() + 12, geom.height() + 12, 8, 8);
        g.setStroke(SELECTION);
        g.setLineWidth(1.4);
        g.strokeRoundRect(-geom.halfWidth() - 6, -geom.halfHeight() - 6,
            geom.width() + 12, geom.height() + 12, 8, 8);
    }

    private static void drawHover(GraphicsContext g, ComponentGeometry geom) {
        g.setStroke(HOVER);
        g.setLineDashes(3, 3);
        g.setLineWidth(1);
        g.strokeRoundRect(-geom.halfWidth() - 4, -geom.halfHeight() - 4,
            geom.width() + 8, geom.height() + 8, 6, 6);
        g.setLineDashes();
    }

    private static void drawTerminals(GraphicsContext g, ComponentGeometry geom) {
        g.setFill(TERMINAL);
        for (var t : geom.terminals()) {
            g.fillOval(t.xOffset() - 3.5, t.yOffset() - 3.5, 7, 7);
            g.setStroke(Color.WHITE);
            g.setLineWidth(1);
            g.strokeOval(t.xOffset() - 3.5, t.yOffset() - 3.5, 7, 7);
        }
    }

    private static void drawVoltageSource(GraphicsContext g, CircuitComponent.VoltageSource v) {
        Color accent = switch (v.kind()) {
            case GATE -> GATE_ACCENT;
            case STATIC -> Color.web("#475569");
            default -> ACCENT;
        };
        // Single output lead exits on the right.
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(22, 0, 45, 0);
        // Body: circle
        g.setFill(Color.WHITE);
        g.fillOval(-22, -22, 44, 44);
        g.setStroke(accent);
        g.setLineWidth(1.6);
        g.strokeOval(-22, -22, 44, 44);
        // Glyph inside
        g.setFill(accent);
        g.setFont(Font.font("System", 11));
        g.setTextAlign(TextAlignment.CENTER);
        String glyph = switch (v.kind()) {
            case QUADRATURE -> "I/Q";
            case GATE -> "G";
            case REAL -> "V";
            case STATIC -> "\u2261";
        };
        g.fillText(glyph, 0, 4);
        // Label below: one line with the DAW track / source name, one line with the amplitude kind.
        drawLabel(g, v.name(), INK, 0, 36);
        drawSubLabel(g, kindSubtitle(v), accent.darker(), 0, 50);
    }

    private static String kindSubtitle(CircuitComponent.VoltageSource v) {
        return switch (v.kind()) {
            case QUADRATURE -> "I/Q \u2022 " + (long) v.carrierHz() + " Hz";
            case REAL -> "real \u00b1" + v.maxAmplitude();
            case STATIC -> "static " + v.maxAmplitude();
            case GATE -> "gate";
        };
    }

    private static void drawSwitch(GraphicsContext g, CircuitComponent.SwitchComponent s) {
        // Main wires
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-45, 0, -18, 0);
        g.strokeLine(18, 0, 45, 0);
        // Tilted blade
        g.setStroke(GATE_ACCENT);
        g.setLineWidth(2);
        g.strokeLine(-18, 0, 14, -14);
        // Contact dots
        g.setFill(INK);
        g.fillOval(-20, -2, 4, 4);
        g.fillOval(16, -2, 4, 4);
        // Ctl lead
        g.setStroke(GATE_ACCENT.deriveColor(0, 1, 1, 0.7));
        g.setLineDashes(3, 2);
        g.strokeLine(0, 14, 0, 35);
        g.setLineDashes();
        drawLabel(g, s.name(), INK, 0, -22);
    }

    private static void drawCoil(GraphicsContext g, CircuitComponent.Coil c) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-45, 0, -26, 0);
        g.strokeLine(26, 0, 45, 0);
        // Four arcs forming the coil
        g.setStroke(COIL_ACCENT);
        g.setLineWidth(1.8);
        for (int i = 0; i < 4; i++) {
            double cx = -18 + i * 12;
            g.strokeArc(cx - 6, -6, 12, 12, 0, 180, javafx.scene.shape.ArcType.OPEN);
        }
        drawLabel(g, c.name(), INK, 0, 20);
    }

    private static void drawProbe(GraphicsContext g, CircuitComponent.Probe p) {
        // Single input lead enters on the left.
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-45, 0, -18, 0);
        // Body: rectangle with triangle inside
        g.setFill(Color.WHITE);
        g.fillRoundRect(-18, -16, 36, 32, 4, 4);
        g.setStroke(PROBE_ACCENT);
        g.setLineWidth(1.6);
        g.strokeRoundRect(-18, -16, 36, 32, 4, 4);
        g.setFill(PROBE_ACCENT);
        g.fillPolygon(new double[]{-8, 8, 0}, new double[]{-7, -7, 7}, 3);
        drawLabel(g, p.name(), INK, 0, 28);
    }

    private static void drawGround(GraphicsContext g) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        // Vertical lead
        g.strokeLine(0, -20, 0, -6);
        // Three horizontal bars of decreasing width
        g.strokeLine(-14, -6, 14, -6);
        g.strokeLine(-10, 0, 10, 0);
        g.strokeLine(-6, 6, 6, 6);
    }

    private static void drawResistor(GraphicsContext g, CircuitComponent.Resistor r) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-45, 0, -26, 0);
        g.strokeLine(26, 0, 45, 0);
        // Zigzag
        double[] xs = new double[]{-26, -20, -12, -4, 4, 12, 20, 26};
        double[] ys = new double[]{0, -8, 8, -8, 8, -8, 8, 0};
        g.strokePolyline(xs, ys, xs.length);
        drawLabel(g, r.name(), INK, 0, 20);
    }

    private static void drawCapacitor(GraphicsContext g, CircuitComponent.Capacitor c) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-45, 0, -6, 0);
        g.strokeLine(6, 0, 45, 0);
        // Two plates
        g.strokeLine(-6, -14, -6, 14);
        g.strokeLine(6, -14, 6, 14);
        drawLabel(g, c.name(), INK, 0, 24);
    }

    private static void drawInductor(GraphicsContext g, CircuitComponent.Inductor l) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-45, 0, -26, 0);
        g.strokeLine(26, 0, 45, 0);
        for (int i = 0; i < 4; i++) {
            double cx = -18 + i * 12;
            g.strokeArc(cx - 6, -6, 12, 12, 0, 180, javafx.scene.shape.ArcType.OPEN);
        }
        drawLabel(g, l.name(), INK, 0, 20);
    }

    private static void drawTransformer(GraphicsContext g, CircuitComponent.IdealTransformer t) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        // Primary side
        g.strokeLine(-50, -20, -26, -20);
        g.strokeLine(-50, 20, -26, 20);
        // Secondary side
        g.strokeLine(26, -20, 50, -20);
        g.strokeLine(26, 20, 50, 20);
        // Cores
        for (int i = 0; i < 3; i++) {
            double cy = -14 + i * 14;
            g.strokeArc(-28, cy - 6, 12, 12, 90, 180, javafx.scene.shape.ArcType.OPEN);
            g.strokeArc(16, cy - 6, 12, 12, -90, 180, javafx.scene.shape.ArcType.OPEN);
        }
        drawLabel(g, t.name(), INK, 0, 42);
    }

    private static void drawLabel(GraphicsContext g, String text, Color color, double x, double y) {
        g.setFill(color);
        g.setFont(Font.font("System", 11));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(text, x, y);
    }

    private static void drawSubLabel(GraphicsContext g, String text, Color color, double x, double y) {
        g.setFill(color);
        g.setFont(Font.font("System", 9));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(text, x, y);
    }
}
