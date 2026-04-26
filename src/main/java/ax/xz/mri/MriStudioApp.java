package ax.xz.mri;

import ax.xz.mri.ui.workbench.StudioShell;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import software.coley.bentofx.Bento;

import java.io.File;
import java.util.Optional;

/** MRI Studio entry point. */
public class MriStudioApp extends Application {
    private StudioShell shell;

    @Override
    public void start(Stage stage) {
        shell = new StudioShell();
        var scene = new Scene(shell, 1600, 980);
        addStylesheet(scene, Bento.class.getResource("/bento.css"));
        addStylesheet(scene, getClass().getResource("/ax/xz/mri/ui/theme/studio.css"));

        stage.setTitle("MRI Studio");
        stage.setScene(scene);
        shell.initialize(stage);
        stage.show();

        getParameters().getUnnamed().stream()
            .findFirst()
            .map(File::new)
            .flatMap(MriStudioApp::resolveProjectDir)
            .ifPresent(this::autoOpen);
    }

    @Override
    public void stop() {
        if (shell != null) shell.dispose();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private static void addStylesheet(Scene scene, java.net.URL url) {
        if (url != null) scene.getStylesheets().add(url.toExternalForm());
    }

    /** Treat the argument as a project directory, or as a manifest file inside one. */
    private static Optional<File> resolveProjectDir(File f) {
        if (!f.exists()) return Optional.empty();
        if (f.isDirectory() && new File(f, "mri-project.toml").exists()) return Optional.of(f);
        if (f.getName().equals("mri-project.toml") && f.getParentFile() != null) return Optional.of(f.getParentFile());
        return Optional.empty();
    }

    private void autoOpen(File dir) {
        try { shell.controller().openProjectDirectory(dir); }
        catch (Exception ex) {
            shell.controller().session().messages.logError("Startup", "Failed to auto-open " + dir, ex);
        }
    }
}
