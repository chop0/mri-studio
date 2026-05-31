package ax.xz.mri.ui.preview;

import ax.xz.mri.model.procedure.ProcedureStarterLibrary;
import ax.xz.mri.ui.tutorial.TutorialLibrary;
import ax.xz.mri.ui.workbench.StudioShell;
import ax.xz.mri.ui.wizard.starters.SimConfigTemplate;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import software.coley.bentofx.Bento;

import javax.imageio.ImageIO;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Boots the full {@link StudioShell} on a fresh, empty project so the
 * {@link ax.xz.mri.ui.tutorial.WelcomePane} appears, then starts the NV
 * adaptive-coherent tutorial and snapshots the spotlight + arrow + bubble
 * overlay at each milestone. Manual UX verification for Part 13.
 */
public final class TutorialPreview extends Application {
    private static final Path OUT = Path.of("build", "tutorial-preview");

    public static void main(String[] args) { launch(args); }

    @Override
    public void start(Stage stage) throws Exception {
        Files.createDirectories(OUT);
        for (var existing : Files.newDirectoryStream(OUT, "*.png")) Files.deleteIfExists(existing);

        var shell = new StudioShell();
        var scene = new Scene(shell, 1500, 940);
        addStylesheet(scene, Bento.class.getResource("/bento.css"));
        addStylesheet(scene, getClass().getResource("/ax/xz/mri/ui/theme/studio.css"));
        stage.setTitle("MRI Studio – Tutorial Preview");
        stage.setScene(scene);
        shell.initialize(stage);
        stage.show();

        var session = shell.controller().session();
        var runner = shell.tutorialRunner();

        var steps = new ArrayList<Step>();

        steps.add(new Step("01-welcome",
            "fresh project → welcome pane with two tutorial cards + Open Project",
            () -> {}));

        steps.add(new Step("02-tutorial-step1-spotlight",
            "NV tutorial started — spotlight + arrow + bubble on the menu bar (New ▸ Simulation Config)",
            () -> runner.start(TutorialLibrary.NV_COHERENT)));

        steps.add(new Step("03-tutorial-step2-spotlight",
            "after creating the NV config — bubble advances to step 2 (New ▸ Procedure)",
            () -> session.project.createSimConfig("NV diamond",
                SimConfigTemplate.NV_CENTRE_DIAMOND,
                SimConfigTemplate.NV_CENTRE_DIAMOND.defaultPhysics())));

        steps.add(new Step("04-tutorial-complete",
            "after creating the procedure — tutorial complete, overlay clears, workbench populated",
            () -> {
                var src = ProcedureStarterLibrary.byId("nv-adaptive-coherent").orElseThrow().source();
                session.project.createProcedure("NV adaptive coherent", src);
            }));

        chain(scene, steps, 0);
    }

    private void chain(Scene scene, List<Step> steps, int idx) {
        if (idx >= steps.size()) { Platform.exit(); return; }
        var step = steps.get(idx);
        Platform.runLater(() -> {
            try { step.action.run(); } catch (Exception ex) { ex.printStackTrace(); }
            new AnimationTimer() {
                int frames = 0;
                @Override public void handle(long now) {
                    if (frames++ < 30) return;
                    stop();
                    try { snapshot(scene, step.name, step.description); }
                    catch (Exception ex) { ex.printStackTrace(); }
                    chain(scene, steps, idx + 1);
                }
            }.start();
        });
    }

    private static void snapshot(Scene scene, String name, String description) throws Exception {
        var root = (Parent) scene.getRoot();
        root.applyCss();
        root.layout();
        var params = new SnapshotParameters();
        params.setFill(Color.web("#eff1f4"));
        WritableImage img = root.snapshot(params, null);
        var out = OUT.resolve(name + ".png").toFile();
        ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", out);
        System.out.printf("[tutorial-preview] %s — %s  (%dx%d)%n",
            name, description, (int) img.getWidth(), (int) img.getHeight());
    }

    private static void addStylesheet(Scene scene, URL url) {
        if (url != null) scene.getStylesheets().add(url.toExternalForm());
    }

    private static final class Step {
        final String name;
        final String description;
        final Runnable action;
        Step(String name, String description, Runnable action) {
            this.name = name; this.description = description; this.action = action;
        }
    }
}
