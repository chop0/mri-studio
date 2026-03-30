package ax.xz.mri;

import atlantafx.base.theme.CupertinoDark;
import ax.xz.mri.ui.StudioWorkbench;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * MRI Studio — entry point.
 * Applies the CupertinoDark AtlantaFX theme, then loads the main workbench.
 */
public class MriStudioApp extends Application {

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new CupertinoDark().getUserAgentStylesheet());

        var workbench = new StudioWorkbench();
        var scene     = new Scene(workbench, 1400, 900);

        var css = getClass().getResource("/ax/xz/mri/ui/theme/studio.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        stage.setTitle("MRI Studio");
        stage.setScene(scene);
        stage.show();

        // Support launching with a file argument: ./gradlew run --args="path/to/bloch_data.json"
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
