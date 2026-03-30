package ax.xz.mri;

import ax.xz.mri.ui.StudioWorkbench;
import ax.xz.mri.ui.aerofx.AeroFX;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * MRI Studio — entry point.
 * Uses AeroFX win7.css as the user-agent stylesheet (replaces Modena).
 * The CSS includes all necessary lookup color definitions.
 */
public class MriStudioApp extends Application {

    @Override
    public void start(Stage stage) {
        // Replace Modena entirely with AeroFX + our Modena-compat definitions
        AeroFX.style();

        var workbench = new StudioWorkbench();
        var scene     = new Scene(workbench, 1400, 900);

        var css = getClass().getResource("/ax/xz/mri/ui/theme/studio.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        stage.setTitle("MRI Studio");
        stage.setScene(scene);
        stage.show();

        var params = getParameters().getUnnamed();
        if (!params.isEmpty()) {
            var f = new java.io.File(params.get(0));
            if (f.exists()) workbench.loadFile(f);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
