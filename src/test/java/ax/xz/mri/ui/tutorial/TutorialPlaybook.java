package ax.xz.mri.ui.tutorial;

import ax.xz.mri.state.ProjectState;
import ax.xz.mri.ui.viewmodel.ProjectSessionViewModel;
import ax.xz.mri.ui.workbench.StudioShell;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Test fixture that boots a real {@link StudioShell} (off-screen, never
 * shown — showing a modal in a headless test would hang) and exposes the
 * pieces a tutorial integration test drives: the {@link TutorialRunner}, the
 * project view-model, and anchor-resolution checks.
 *
 * <p>Must be constructed and used entirely on the JavaFX application thread
 * (wrap calls in {@code FxTestSupport.runOnFxThread}). Document creation is
 * performed through the same {@link ProjectSessionViewModel} API a wizard's
 * {@code resultFactory} invokes on Finish — so the runner observes the exact
 * {@link ProjectState} transitions a real click-through would produce, while
 * staying robust in headless CI (no Stage is shown).
 */
final class TutorialPlaybook {

    final StudioShell shell;
    final TutorialRunner runner;
    final ProjectSessionViewModel project;
    private final Stage stage;

    private TutorialPlaybook() {
        UiAnchors.clearForTest();
        shell = new StudioShell();
        stage = new Stage();
        // Attach a scene so menu-bar / pane nodes report a non-null scene
        // (the runner gates anchor rendering on getScene() != null). We never
        // call stage.show() — only the scene graph attachment matters. Force a
        // CSS + layout pass so the MenuBar skin builds its top-level menu
        // buttons (the spotlight targets), which otherwise only materialise
        // when the stage is shown.
        stage.setScene(new Scene(shell, 1000, 700));
        shell.initialize(stage);
        shell.applyCss();
        shell.layout();
        runner = shell.tutorialRunner();
        project = shell.controller().session().project;
    }

    static TutorialPlaybook boot() { return new TutorialPlaybook(); }

    /** Current 0-based step the runner is on, or -1 when idle/complete. */
    int currentStep() { return runner.currentStepIndexProperty().get(); }

    /** True when the anchor resolves to a live, on-scene node — the clickable target the bubble points at. */
    boolean anchorResolves(AnchorKey key) {
        var node = UiAnchors.lookup(key);
        return node != null && node.getScene() != null;
    }

    ProjectState state() { return project.project(); }

    void dispose() {
        runner.stop();
        shell.dispose();
    }
}
