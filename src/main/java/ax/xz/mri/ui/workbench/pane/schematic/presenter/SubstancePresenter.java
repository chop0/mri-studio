package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import module ax.xz.mri;
import module javafx.controls;
import module javafx.graphics;

// Non-exported types — surfaced individually.
import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SubstanceDocument;
import ax.xz.mri.ui.workbench.pane.schematic.ComponentGeometry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.INK;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.drawLabel;

/**
 * Schematic glyph for a {@link CircuitComponent.Substance} block — a
 * {@link SubstanceDocument} placed in the FOV. Drawn as a rounded shaded
 * tile with the substance kind labelled, the linked document's name
 * beneath, and (for NV) typed ports on each edge.
 *
 * <p>Magnetic coupling is implicit / ambient — no electrical ports, no
 * MNA contribution. The block exists for layout, port wiring (laser
 * control, photon-counter routing), and inspector-side configuration.
 */
final class SubstancePresenter implements ComponentPresenter {

    private static final Color SUBSTANCE_FILL    = Color.web("#fef6e4");
    private static final Color SUBSTANCE_STROKE  = Color.web("#8d5524");
    private static final Color CONTROL_ACCENT    = Color.web("#1f6f30");
    private static final Color OPTICAL_ACCENT    = Color.web("#b3531a");

    private final CircuitComponent.Substance s;
    private final ComponentGeometry geometry;

    SubstancePresenter(CircuitComponent.Substance s) {
        this.s = s;
        this.geometry = buildGeometry(s);
    }

    private static ComponentGeometry buildGeometry(CircuitComponent.Substance s) {
        return switch (s.kind()) {
            case CONTINUOUS_MAGNETISATION -> new ComponentGeometry(120, 70, List.of());
            case NV                       -> new ComponentGeometry(120, 70, List.of(
                new ComponentGeometry.Terminal("laser_on",  -60, -16),
                new ComponentGeometry.Terminal("clicks_red",  60, -16)
            ));
        };
    }

    @Override public ComponentGeometry geometry() { return geometry; }

    @Override
    public void drawBody(GraphicsContext g) {
        // Warm-beige rounded body — visually distinct from cool-grey electrical glyphs.
        g.setFill(SUBSTANCE_FILL);
        g.fillRoundRect(-50, -28, 100, 56, 10, 10);
        g.setStroke(SUBSTANCE_STROKE);
        g.setLineWidth(1.6);
        g.strokeRoundRect(-50, -28, 100, 56, 10, 10);

        g.setFill(SUBSTANCE_STROKE);
        g.setFont(Font.font("System", 10));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(kindGlyph(s.kind()), 0, -8);

        if (s.kind() == CircuitComponent.Substance.Kind.NV) {
            // Control input on the left, optical output on the right.
            g.setStroke(CONTROL_ACCENT);
            g.setLineWidth(1.4);
            g.strokeLine(-50, -16, -56, -16);
            g.setFill(CONTROL_ACCENT);
            g.setFont(Font.font("System", 8));
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText("laser_on", -58, -13);

            g.setStroke(OPTICAL_ACCENT);
            g.strokeLine(50, -16, 56, -16);
            g.setFill(OPTICAL_ACCENT);
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText("clicks_red", 58, -13);
        }

        drawLabel(g, s.name(), INK, 0, 24);
    }

    private static String kindGlyph(CircuitComponent.Substance.Kind k) {
        return switch (k) {
            case CONTINUOUS_MAGNETISATION -> "PROTON";
            case NV                       -> "NV";
        };
    }

    @Override
    public void buildInspector(VBox container, InspectorEnv env) {
        var repo = env.repository().get();
        var names = new ArrayList<String>();
        var idByName = new LinkedHashMap<String, ProjectNodeId>();
        if (repo != null) {
            for (var subId : repo.substanceIds()) {
                var subDoc = repo.substance(subId);
                if (subDoc != null) {
                    names.add(subDoc.name());
                    idByName.put(subDoc.name(), subDoc.id());
                }
            }
        }
        var row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        var label = new Label("Document");
        label.setPrefWidth(100);
        row.getChildren().add(label);
        var combo = new ComboBox<>(FXCollections.observableArrayList(names));
        combo.setPrefWidth(170);
        String currentName = null;
        if (repo != null) {
            var subDoc = repo.substance(s.substanceDocId());
            if (subDoc != null) currentName = subDoc.name();
        }
        combo.setValue(currentName);
        combo.setOnAction(e -> {
            var picked = combo.getValue();
            if (picked == null) return;
            var pickedId = idByName.get(picked);
            if (pickedId == null) return;
            var pickedDoc = repo == null ? null : repo.substance(pickedId);
            if (pickedDoc == null) return;
            env.session().replaceComponent(s.withSubstanceDocId(pickedDoc.id()).withKind(mapKind(pickedDoc)));
        });
        row.getChildren().add(combo);
        container.getChildren().add(row);

        var placement = new Label("Placement (FOV-relative)");
        placement.setStyle("-fx-font-weight: bold; -fx-padding: 8 0 0 0;");
        container.getChildren().add(placement);

        container.getChildren().add(InspectorFields.doubleField("x offset (m)", s.xMetres(),
            x -> env.session().replaceComponent(s.withOffset(x, s.yMetres(), s.zMetres()))));
        container.getChildren().add(InspectorFields.doubleField("y offset (m)", s.yMetres(),
            y -> env.session().replaceComponent(s.withOffset(s.xMetres(), y, s.zMetres()))));
        container.getChildren().add(InspectorFields.doubleField("z offset (m)", s.zMetres(),
            z -> env.session().replaceComponent(s.withOffset(s.xMetres(), s.yMetres(), z))));

    }

    private static CircuitComponent.Substance.Kind mapKind(SubstanceDocument doc) {
        return switch (doc.substance()) {
            case ax.xz.mri.model.substance.ContinuousMagnetisation ignored ->
                CircuitComponent.Substance.Kind.CONTINUOUS_MAGNETISATION;
            case ax.xz.mri.model.substance.NvEnsemble ignored ->
                CircuitComponent.Substance.Kind.NV;
        };
    }

    @Override public int autoLayoutColumn() { return 3; }
    @Override public String displayName() { return "Substance"; }
}
