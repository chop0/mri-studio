package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.ui.workbench.pane.schematic.ComponentGeometry;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.ArcType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.COIL_ACCENT;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.INK;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.drawGroundTail;
import static ax.xz.mri.ui.workbench.pane.schematic.presenter.SchematicInk.drawLabel;

/** Physical coil — the bridge between circuit and FOV. */
final class CoilPresenter implements ComponentPresenter {
    private static final ComponentGeometry GEOM = new ComponentGeometry(90, 60, List.of(
        new ComponentGeometry.Terminal("in", -45, 0)
    ));

    private final CircuitComponent.Coil c;

    CoilPresenter(CircuitComponent.Coil c) { this.c = c; }

    @Override public ComponentGeometry geometry() { return GEOM; }

    @Override
    public void drawBody(GraphicsContext g) {
        g.setStroke(INK);
        g.setLineWidth(1.4);
        g.strokeLine(-45, 0, -26, 0);
        g.setStroke(COIL_ACCENT);
        g.setLineWidth(1.8);
        for (int i = 0; i < 4; i++) {
            double cx = -18 + i * 12;
            g.strokeArc(cx - 6, -6, 12, 12, 0, 180, ArcType.OPEN);
        }
        drawGroundTail(g, 26);
        drawLabel(g, c.name(), INK, 0, 24);
    }

    @Override
    public void buildInspector(VBox container, InspectorEnv env) {
        var row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        var label = new Label("Eigenfield");
        label.setPrefWidth(100);
        row.getChildren().add(label);

        var repo = env.repository().get();
        var names = new ArrayList<String>();
        var idByName = new LinkedHashMap<String, ProjectNodeId>();
        if (repo != null) {
            for (var efId : repo.eigenfieldIds()) {
                if (repo.node(efId) instanceof EigenfieldDocument ef) {
                    names.add(ef.name());
                    idByName.put(ef.name(), ef.id());
                }
            }
        }
        var combo = new ComboBox<>(FXCollections.observableArrayList(names));
        combo.setPrefWidth(130);
        String current = null;
        if (repo != null && c.eigenfieldId() != null
                && repo.node(c.eigenfieldId()) instanceof EigenfieldDocument ef) {
            current = ef.name();
        }
        combo.setValue(current);
        combo.setOnAction(e -> {
            var picked = combo.getValue();
            if (picked == null) return;
            env.session().replaceComponent(c.withEigenfieldId(idByName.get(picked)));
        });
        row.getChildren().add(combo);

        var jump = new Button("Open");
        jump.setOnAction(e -> {
            if (c.eigenfieldId() != null && env.onJumpToEigenfield() != null) {
                env.onJumpToEigenfield().accept(c.eigenfieldId());
            }
        });
        jump.setDisable(c.eigenfieldId() == null);
        row.getChildren().add(jump);
        container.getChildren().add(row);

        container.getChildren().add(InspectorFields.doubleField(
            "Self-L (H)", c.selfInductanceHenry(),
            v -> env.session().replaceComponent(c.withSelfInductanceHenry(v))));
        container.getChildren().add(InspectorFields.doubleField(
            "Series R (ohms)", c.seriesResistanceOhms(),
            v -> env.session().replaceComponent(c.withSeriesResistanceOhms(v))));
    }

    @Override public int autoLayoutColumn() { return 2; }
    @Override public String displayName() { return "Coil"; }
}
