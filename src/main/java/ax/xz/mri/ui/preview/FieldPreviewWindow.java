package ax.xz.mri.ui.preview;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.model.simulation.dsl.EigenfieldScript;
import ax.xz.mri.model.simulation.dsl.EigenfieldScriptEngine;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.service.circuit.path.CoilPath;
import ax.xz.mri.service.circuit.path.FieldPreview;
import ax.xz.mri.ui.eigenfield.EigenfieldPreviewCanvas;
import ax.xz.mri.util.SiFormat;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * 3D popup that previews the magnetic field a coil would produce given a
 * coil current. Reuses {@link EigenfieldPreviewCanvas} to do the actual
 * rendering; we feed it a "scaled script" that pre-multiplies the
 * dimensionless eigenfield shape by {@code I × sensitivity} so the arrows
 * read in physical Tesla.
 *
 * <p>The window is non-modal, lives independently of its caller, and is safe
 * to open from the inspector's "View 3D" button. The caller decides whether
 * to keep a reference (e.g. to reposition or close it later) — typically you
 * just call {@link #show(EigenfieldDocument, CircuitComponent.Coil, CoilPath, double)}
 * and forget.
 */
public final class FieldPreviewWindow {
    private FieldPreviewWindow() {}

    /**
     * Open a non-modal preview window for the given coil/clip combination.
     * Returns the {@link Stage} so callers can position or close it; the
     * window is already shown when this method returns.
     *
     * @param eigenfield eigenfield doc supplying the dimensionless shape script
     * @param coil       coil whose sensitivity scales the shape into Tesla
     * @param path       result of the path analyzer (for current and frequency labels)
     * @param sourceAmplitudeVolts amplitude of the clip on the driving source
     */
    public static Stage show(EigenfieldDocument eigenfield,
                             CircuitComponent.Coil coil,
                             CoilPath path,
                             double sourceAmplitudeVolts) {
        var stage = new Stage();
        stage.setTitle("Field preview — " + coil.name());

        EigenfieldScript shape;
        String compileError = null;
        try {
            shape = eigenfield != null
                ? EigenfieldScriptEngine.compile(eigenfield.script())
                : (x, y, z) -> Vec3.ZERO;
        } catch (Throwable t) {
            shape = (x, y, z) -> Vec3.ZERO;
            compileError = "Eigenfield script failed to compile: " + t.getMessage();
        }

        double currentAmps = path.currentAmpsAt(sourceAmplitudeVolts);
        var canvas = new EigenfieldPreviewCanvas();
        canvas.setPrefSize(560, 480);
        canvas.scriptProperty().set(FieldPreview.scaledScript(shape, coil.sensitivityT_per_A(), currentAmps));

        var info = buildInfoStrip(eigenfield, coil, path, currentAmps, compileError);
        var controls = buildViewControls(canvas);

        var root = new BorderPane(canvas);
        root.setTop(info);
        root.setBottom(controls);
        BorderPane.setMargin(info, new Insets(10, 12, 6, 12));
        BorderPane.setMargin(controls, new Insets(6, 12, 10, 12));

        var scene = new Scene(root, 640, 600);
        // Match the studio's existing stylesheet so the popup blends in.
        var css = FieldPreviewWindow.class.getResource("/ax/xz/mri/ui/theme/studio.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        stage.setScene(scene);
        // Stop the embedded animation timer when the window closes — otherwise
        // the JavaFX runtime keeps it alive through GC roots.
        stage.setOnHidden(e -> canvas.stop());

        stage.show();
        return stage;
    }

    private static Region buildInfoStrip(EigenfieldDocument eigenfield,
                                          CircuitComponent.Coil coil,
                                          CoilPath path,
                                          double currentAmps,
                                          String compileError) {
        var box = new VBox(2);
        var title = new Label(coil.name()
            + (eigenfield != null ? " — " + eigenfield.name() : ""));
        title.getStyleClass().add("inspector-title");

        var line1 = new Label(String.format(
            "Current ≈ %s   ·   Sensitivity = %s   ·   Carrier = %s",
            SiFormat.amps(currentAmps),
            SiFormat.teslaPerAmp(coil.sensitivityT_per_A()),
            SiFormat.hz(path.frequencyHz())
        ));
        line1.getStyleClass().add("clip-inspector-hint");

        box.getChildren().addAll(title, line1);
        if (!path.warnings().isEmpty()) {
            for (var w : path.warnings()) {
                var warn = new Label("⚠ " + w);
                warn.getStyleClass().add("clip-inspector-hint");
                warn.setWrapText(true);
                box.getChildren().add(warn);
            }
        }
        if (compileError != null) {
            var err = new Label("⚠ " + compileError);
            err.getStyleClass().add("clip-inspector-hint");
            err.setWrapText(true);
            box.getChildren().add(err);
        }
        return box;
    }

    private static Region buildViewControls(EigenfieldPreviewCanvas canvas) {
        var resetBtn = new Button("Reset view");
        resetBtn.setOnAction(e -> canvas.resetView());

        var halfExtent = new Slider(0.02, 0.5, canvas.halfExtentMProperty().get());
        halfExtent.setMajorTickUnit(0.1);
        halfExtent.setMinorTickCount(4);
        halfExtent.valueProperty().addListener((obs, o, n) ->
            canvas.halfExtentMProperty().set(n.doubleValue()));
        canvas.halfExtentMProperty().addListener((obs, o, n) -> {
            if (Math.abs(halfExtent.getValue() - n.doubleValue()) > 1e-9) halfExtent.setValue(n.doubleValue());
        });
        var halfLabel = new Label();
        halfLabel.textProperty().bind(canvas.halfExtentMProperty().asString("Half-extent: %.2f m"));

        var samples = new Slider(3, 15, canvas.samplesPerAxisProperty().get());
        samples.setMajorTickUnit(2);
        samples.setBlockIncrement(1);
        samples.setSnapToTicks(true);
        samples.valueProperty().addListener((obs, o, n) ->
            canvas.samplesPerAxisProperty().set(n.intValue()));
        canvas.samplesPerAxisProperty().addListener((obs, o, n) -> {
            if (Math.abs(samples.getValue() - n.doubleValue()) > 0.5) samples.setValue(n.doubleValue());
        });
        var samplesLabel = new Label();
        samplesLabel.textProperty().bind(canvas.samplesPerAxisProperty().asString("Density: %d³"));

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var row = new HBox(8, resetBtn, halfLabel, halfExtent, samplesLabel, samples, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

}
