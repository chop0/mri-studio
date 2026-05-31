package ax.xz.mri.ui.preview;

import ax.xz.mri.dsl.EigenfieldEngine;
import ax.xz.mri.dsl.EigenfieldScript;
import ax.xz.mri.model.nv.NvAxis;
import ax.xz.mri.model.nv.NvCentre;
import ax.xz.mri.model.simulation.dsl.EigenfieldStarterLibrary;
import ax.xz.mri.ui.eigenfield.EigenfieldPreviewCanvas;
import ax.xz.mri.ui.substance.NvScatter3DCanvas;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Snapshots the B-field viewer ({@link EigenfieldPreviewCanvas}) and the NV
 * eigenfield overlay ({@link NvScatter3DCanvas}) at several zoom levels to
 * verify that the arrow density adapts to zoom — more field arrows appear as
 * you zoom in — and that both surfaces render the field identically through the
 * shared {@code VectorFieldArrowRenderer}.
 */
public final class FieldDensityPreview extends Application {

    private static final Path OUT = Path.of("build", "field-density-preview");

    public static void main(String[] args) { launch(args); }

    @Override
    public void start(Stage stage) throws Exception {
        Files.createDirectories(OUT);

        EigenfieldScript field = EigenfieldEngine.compile(
            EigenfieldStarterLibrary.byId("lorentzian-dipole").orElseThrow().source());
        double half = EigenfieldPreviewCanvas.autoDetectHalfExtent(field);

        var fieldCanvas = new EigenfieldPreviewCanvas();
        fieldCanvas.scriptProperty().set(field);
        fieldCanvas.halfExtentMProperty().set(half);
        fieldCanvas.setPreset(0.7, 0.35);

        var nvCanvas = new NvScatter3DCanvas();
        nvCanvas.overlayScriptProperty().set(field);
        nvCanvas.halfExtentMProperty().set(half);
        nvCanvas.setPreset(0.7, 0.35);
        var centres = new ArrayList<NvCentre>();
        for (int i = 0; i < 6; i++) {
            double t = -0.6 + 1.2 * i / 5.0;
            centres.add(new NvCentre(t * half, 0.15 * half, 0.2 * half, NvAxis.AXIS_PLUS_Z));
        }
        nvCanvas.centres().setAll(centres);

        var scene = new Scene(fieldCanvas, 720, 560);
        stage.setScene(scene);
        stage.setTitle("Field density preview");
        stage.show();

        var steps = new ArrayList<Step>();
        steps.add(new Step("01-bfield-zoom-out", fieldCanvas, () -> {
            scene.setRoot(fieldCanvas); fieldCanvas.zoomProperty().set(0.5);
        }));
        steps.add(new Step("02-bfield-zoom-default", fieldCanvas, () -> fieldCanvas.zoomProperty().set(1.0)));
        steps.add(new Step("03-bfield-zoom-in", fieldCanvas, () -> fieldCanvas.zoomProperty().set(3.5)));
        steps.add(new Step("04-nv-overlay-zoom-default", nvCanvas, () -> {
            scene.setRoot(nvCanvas); nvCanvas.zoomProperty().set(1.0);
        }));
        steps.add(new Step("05-nv-overlay-zoom-in", nvCanvas, () -> nvCanvas.zoomProperty().set(3.5)));

        chain(steps, 0, () -> { fieldCanvas.stop(); nvCanvas.stop(); Platform.exit(); });
    }

    private record Step(String name, Parent root, Runnable action) {}

    private void chain(List<Step> steps, int idx, Runnable done) {
        if (idx >= steps.size()) { done.run(); return; }
        var step = steps.get(idx);
        Platform.runLater(() -> {
            step.action().run();
            new AnimationTimer() {
                int frames = 0;
                @Override public void handle(long now) {
                    if (frames++ < 40) return;     // let the dirty-redraw loop paint
                    stop();
                    try { snapshot(step); } catch (Exception ex) { ex.printStackTrace(); }
                    chain(steps, idx + 1, done);
                }
            }.start();
        });
    }

    private void snapshot(Step step) throws Exception {
        step.root().applyCss();
        step.root().layout();
        var params = new SnapshotParameters();
        WritableImage img = step.root().snapshot(params, null);
        var out = OUT.resolve(step.name() + ".png").toFile();
        ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", out);
        System.out.printf("[field-density] %s (%dx%d)%n", step.name(), (int) img.getWidth(), (int) img.getHeight());
    }
}
