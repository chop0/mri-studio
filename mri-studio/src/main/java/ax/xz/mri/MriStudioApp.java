package ax.xz.mri;

import ax.xz.mri.ui.aerofx.AeroFX;
import ax.xz.mri.ui.workbench.StudioShell;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * MRI Studio — entry point.
 * Uses AeroFX win7.css as the user-agent stylesheet (replaces Modena).
 * The CSS includes all necessary lookup color definitions.
 */
public class MriStudioApp extends Application {
    private StudioShell shell;

    @Override
    public void start(Stage stage) {
        // Replace Modena entirely with AeroFX + our Modena-compat definitions
        AeroFX.style();

        shell = new StudioShell();
        var scene = new Scene(shell, 1600, 980);

        var css = getClass().getResource("/ax/xz/mri/ui/theme/studio.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        stage.setTitle("MRI Studio");
        stage.setScene(scene);
        shell.initialize(stage);
        stage.show();

        var params = getParameters().getUnnamed();
        if (!params.isEmpty()) {
            var f = new java.io.File(params.get(0));
            if (f.exists()) shell.controller().loadFile(f);
        }
    }

    @Override
    public void stop() {
        if (shell != null) shell.dispose();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
