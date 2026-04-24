package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.ui.workbench.pane.schematic.ComponentGeometry;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;

import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.GATE_ACCENT;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.INK;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.drawLabel;

/**
 * Voltage-metadata tap — observes a source picked by name and emits a
 * 0/1 "is a clip playing" signal on {@code out} that downstream switches
 * / muxes / probes read. Drawn as a small boxed dashed-gate glyph to
 * distinguish it from load-bearing components.
 *
 * <p>The referenced source is picked from a dropdown in the inspector —
 * no hack-wire from source to tap. The displayed label under the glyph
 * is the current {@code sourceName} so you can tell at a glance which
 * source a given metadata block is observing.
 */
final class VoltageMetadataPresenter implements ComponentPresenter {
    private static final ComponentGeometry GEOM = new ComponentGeometry(90, 60, List.of(
        new ComponentGeometry.Terminal("out", 45, 0)
    ));

    private final CircuitComponent.VoltageMetadata m;

    VoltageMetadataPresenter(CircuitComponent.VoltageMetadata m) { this.m = m; }

    @Override public ComponentGeometry geometry() { return GEOM; }

    @Override
    public void drawBody(GraphicsContext g) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
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
        // Top label = block name; bottom label = source reference so the
        // tap's target is visible at a glance on the canvas.
        drawLabel(g, m.name(), INK, 0, -18);
        String src = m.sourceName();
        drawLabel(g, src == null || src.isBlank() ? "(no source)" : "→ " + src,
            GATE_ACCENT, 0, 26);
    }

    private static String modeGlyph(CircuitComponent.VoltageMetadata.Mode mode) {
        return switch (mode) {
            case ACTIVE -> "ACT";
        };
    }

    @Override
    public void buildInspector(VBox container, InspectorEnv env) {
        var help = new Label(
            "Picks a voltage source by name and emits 1 while that source " +
            "has any active control channel, 0 otherwise. Wire the output " +
            "into a switch ctl, a probe, or anywhere else you need a gate.");
        help.setWrapText(true);
        help.getStyleClass().add("schematic-inspector-hint");
        container.getChildren().add(help);

        // Source-picker dropdown populated from the current document.
        var row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        var label = new Label("Source");
        label.setPrefWidth(100);
        row.getChildren().add(label);

        var names = new ArrayList<String>();
        names.add("(none)");
        for (var c : env.session().doc().components()) {
            if (c instanceof CircuitComponent.VoltageSource src) names.add(src.name());
        }
        var combo = new ComboBox<>(FXCollections.observableArrayList(names));
        combo.setPrefWidth(130);
        combo.setValue(m.sourceName() == null ? "(none)" : m.sourceName());
        combo.setOnAction(e -> {
            String picked = combo.getValue();
            String newSourceName = "(none)".equals(picked) ? null : picked;
            env.session().replaceComponent(m.withSourceName(newSourceName));
        });
        row.getChildren().add(combo);
        container.getChildren().add(row);

        container.getChildren().add(InspectorFields.enumField(
            "Mode", CircuitComponent.VoltageMetadata.Mode.values(), m.mode(),
            mode -> env.session().replaceComponent(m.withMode(mode))));
    }

    @Override public int autoLayoutColumn() { return 1; }
    @Override public String displayName() { return "Voltage metadata"; }
}
