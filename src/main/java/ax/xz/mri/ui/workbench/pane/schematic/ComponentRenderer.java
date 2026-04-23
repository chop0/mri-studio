package ax.xz.mri.ui.workbench.pane.schematic;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.ComponentPosition;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * Stateless renderer that draws circuit components on a JavaFX canvas.
 *
 * <p>Every component is drawn centered at a given world position using only
 * primitive shapes (no image assets). Selected components get a halo;
 * hovered components a subtle ring.
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

    public static void draw(GraphicsContext g, CircuitComponent component, ComponentPosition pos,
                            boolean selected, boolean hovered) {
        g.save();
        g.translate(pos.x(), pos.y());
        if (pos.rotationQuarters() != 0) g.rotate(pos.rotationQuarters() * 90.0);
        if (pos.mirrored()) g.scale(-1, 1);

        var geom = ComponentGeometry.of(component);
        if (selected) drawSelection(g, geom);
        else if (hovered) drawHover(g, geom);

        switch (component) {
            case CircuitComponent.VoltageSource v -> drawVoltageSource(g, v);
            case CircuitComponent.SwitchComponent s -> drawSwitch(g, s);
            case CircuitComponent.Multiplexer m -> drawMultiplexer(g, m);
            case CircuitComponent.Coil c -> drawCoil(g, c);
            case CircuitComponent.Probe p -> drawProbe(g, p);
            case CircuitComponent.Resistor r -> drawResistor(g, r.name());
            case CircuitComponent.Capacitor c -> drawCapacitor(g, c.name());
            case CircuitComponent.Inductor l -> drawInductor(g, l.name());
            case CircuitComponent.ShuntResistor r -> drawShunt(g, r.name(), "R");
            case CircuitComponent.ShuntCapacitor c -> drawShunt(g, c.name(), "C");
            case CircuitComponent.ShuntInductor l -> drawShunt(g, l.name(), "L");
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
        // Main output lead on the right.
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(22, 0, 45, 0);
        // active tap on the bottom (dashed to distinguish from power output).
        g.setStroke(accent.deriveColor(0, 1, 1, 0.6));
        g.setLineDashes(3, 2);
        g.strokeLine(0, 22, 0, 35);
        g.setLineDashes();
        // Body circle.
        g.setFill(Color.WHITE);
        g.fillOval(-22, -22, 44, 44);
        g.setStroke(accent);
        g.setLineWidth(1.6);
        g.strokeOval(-22, -22, 44, 44);
        // Glyph inside — ASCII only.
        g.setFill(accent);
        g.setFont(Font.font("System", 11));
        g.setTextAlign(TextAlignment.CENTER);
        String glyph = switch (v.kind()) {
            case QUADRATURE -> "IQ";
            case GATE -> "G";
            case REAL -> "V";
            case STATIC -> "DC";
        };
        g.fillText(glyph, 0, 4);
        // Just the source name — the kind/amplitude lives in the inspector.
        drawLabel(g, v.name(), INK, 0, -30);
    }

    private static void drawMultiplexer(GraphicsContext g, CircuitComponent.Multiplexer m) {
        // Trapezoidal mux body with the two inputs (a, b) on the left narrow
        // side and common on the right wide side. ctl lead drops from the bottom.
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
        // Port labels inside.
        g.setFill(INK);
        g.setFont(Font.font("System", 9));
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText("a", -36, -17);
        g.fillText("b", -36, 23);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText("c", 36, 3);
        // ctl dashed lead.
        g.setStroke(GATE_ACCENT.deriveColor(0, 1, 1, 0.7));
        g.setLineDashes(3, 2);
        g.strokeLine(0, 24, 0, 45);
        g.setLineDashes();
        drawLabel(g, m.name(), INK, 0, -34);
    }

    private static void drawSwitch(GraphicsContext g, CircuitComponent.SwitchComponent s) {
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

    private static void drawCoil(GraphicsContext g, CircuitComponent.Coil c) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-45, 0, -26, 0);
        g.setStroke(COIL_ACCENT);
        g.setLineWidth(1.8);
        for (int i = 0; i < 4; i++) {
            double cx = -18 + i * 12;
            g.strokeArc(cx - 6, -6, 12, 12, 0, 180, javafx.scene.shape.ArcType.OPEN);
        }
        // Ground tail on the right so it's visually clear the other side is ground.
        drawGroundTail(g, 26);
        drawLabel(g, c.name(), INK, 0, 24);
    }

    private static void drawProbe(GraphicsContext g, CircuitComponent.Probe p) {
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

    private static void drawResistor(GraphicsContext g, String label) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-45, 0, -26, 0);
        g.strokeLine(26, 0, 45, 0);
        double[] xs = new double[]{-26, -20, -12, -4, 4, 12, 20, 26};
        double[] ys = new double[]{0, -8, 8, -8, 8, -8, 8, 0};
        g.strokePolyline(xs, ys, xs.length);
        drawLabel(g, label, INK, 0, 20);
    }

    private static void drawCapacitor(GraphicsContext g, String label) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-45, 0, -6, 0);
        g.strokeLine(6, 0, 45, 0);
        g.strokeLine(-6, -14, -6, 14);
        g.strokeLine(6, -14, 6, 14);
        drawLabel(g, label, INK, 0, 24);
    }

    private static void drawInductor(GraphicsContext g, String label) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-45, 0, -26, 0);
        g.strokeLine(26, 0, 45, 0);
        for (int i = 0; i < 4; i++) {
            double cx = -18 + i * 12;
            g.strokeArc(cx - 6, -6, 12, 12, 0, 180, javafx.scene.shape.ArcType.OPEN);
        }
        drawLabel(g, label, INK, 0, 20);
    }

    /** Shunt passive: one input lead on the left, the element, then an implicit ground. */
    private static void drawShunt(GraphicsContext g, String name, String glyph) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-45, 0, -14, 0);
        // Tiny body — a boxed letter R/L/C — then a ground tail.
        g.setFill(Color.WHITE);
        g.fillRoundRect(-14, -10, 28, 20, 4, 4);
        g.setStroke(INK);
        g.strokeRoundRect(-14, -10, 28, 20, 4, 4);
        g.setFill(INK);
        g.setFont(Font.font("System", 11));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(glyph, 0, 4);
        drawGroundTail(g, 14);
        drawLabel(g, name, INK, 0, 28);
    }

    private static void drawTransformer(GraphicsContext g, CircuitComponent.IdealTransformer t) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-50, -20, -26, -20);
        g.strokeLine(-50, 20, -26, 20);
        g.strokeLine(26, -20, 50, -20);
        g.strokeLine(26, 20, 50, 20);
        for (int i = 0; i < 3; i++) {
            double cy = -14 + i * 14;
            g.strokeArc(-28, cy - 6, 12, 12, 90, 180, javafx.scene.shape.ArcType.OPEN);
            g.strokeArc(16, cy - 6, 12, 12, -90, 180, javafx.scene.shape.ArcType.OPEN);
        }
        drawLabel(g, t.name(), INK, 0, 42);
    }

    /** A short lead plus three ground bars, at the given x offset from the component centre. */
    private static void drawGroundTail(GraphicsContext g, double xStart) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(xStart, 0, xStart + 14, 0);
        double gx = xStart + 14;
        g.strokeLine(gx, -7, gx, 7);
        g.strokeLine(gx + 4, -5, gx + 4, 5);
        g.strokeLine(gx + 8, -3, gx + 8, 3);
    }

    private static void drawLabel(GraphicsContext g, String text, Color color, double x, double y) {
        g.setFill(color);
        g.setFont(Font.font("System", 11));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(text, x, y);
    }
}
