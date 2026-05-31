package ax.xz.mri.ui.preview;

import ax.xz.mri.model.simulation.PhysicsParams;
import ax.xz.mri.ui.workbench.StudioShell;
import ax.xz.mri.ui.wizard.starters.SequenceStarterLibrary;
import ax.xz.mri.ui.wizard.starters.SimConfigTemplate;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import software.coley.bentofx.Bento;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Boots the full {@link StudioShell} (menu bar, sidebars, BentoFX dock layout
 * with analysis panes) with a freshly-created sim config + CPMG sequence open
 * in a tab, and snapshots the result.
 *
 * <p>Where {@link UiPreview} captures only the {@code SequenceEditorPane} in
 * isolation, this preview captures the full app shell so we can verify pane
 * proportions, sidebar positioning, and overall layout — the things the user
 * actually sees on app launch.
 */
public final class ShellPreview extends Application {
    private static final Path OUT = Path.of("build", "shell-preview");

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        Files.createDirectories(OUT);
        for (var existing : Files.newDirectoryStream(OUT, "*.png")) Files.deleteIfExists(existing);

        var shell = new StudioShell();
        var scene = new Scene(shell, 1600, 980);
        addStylesheet(scene, Bento.class.getResource("/bento.css"));
        addStylesheet(scene, getClass().getResource("/ax/xz/mri/ui/theme/studio.css"));
        stage.setTitle("MRI Studio – Shell Preview");
        stage.setScene(scene);
        shell.initialize(stage);
        stage.show();

        var session = shell.controller().session();
        // Build a real CPMG sequence so the editor + analysis panes have
        // something to render — same setup as the project wizard would
        // produce, just driven programmatically.
        var simConfig = session.project.createSimConfig(
            "Low-field MRI", SimConfigTemplate.LOW_FIELD_MRI, PhysicsParams.DEFAULTS);
        var seqDoc = session.project.createSequenceFromStarter(
            "CPMG", simConfig.id(),
            SequenceStarterLibrary.byId("cpmg").orElseThrow());

        // NV-centre-diamond template — same code path the wizard would use.
        // The template's circuitStarter builds the NV substance, three
        // eigenfields (B0, sample, MW), MW I/Q sources + modulator + coil,
        // laser source, and the NvEnsemble substance block. The Ramsey
        // sequence drives Laser + MW I tracks.
        var nvConfig = session.project.createSimConfig(
            "NV diamond", SimConfigTemplate.NV_CENTRE_DIAMOND,
            SimConfigTemplate.NV_CENTRE_DIAMOND.defaultPhysics());
        var ramseyDoc = session.project.createSequenceFromStarter(
            "NV Ramsey", nvConfig.id(),
            SequenceStarterLibrary.byId("nv-ramsey").orElseThrow());

        // The NV substance is auto-created by the NV starter. Find it
        // explicitly (the low-field MRI starter also creates a Water
        // substance now, so first-result isn't what we want).
        var projectState = session.project.project();
        var nvSubstance = projectState.substanceIds().stream()
            .map(projectState::substance)
            .filter(s -> s != null && s.substance() instanceof ax.xz.mri.model.substance.NvEnsemble)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "NV diamond template should have created an NV substance document"));

        var steps = new ArrayList<Step>();
        steps.add(new Step("01-empty-shell",
            "default workbench with no document tabs open",
            () -> {}));

        steps.add(new Step("02-cpmg-open",
            "open the CPMG sequence in a workspace tab",
            () -> shell.controller().openSequenceTab(seqDoc)));

        steps.add(new Step("03-sphere-focused",
            "focus the Sphere analysis pane",
            () -> shell.controller().focusPane(ax.xz.mri.ui.workbench.PaneId.SPHERE)));

        steps.add(new Step("04-magnitude-focused",
            "focus the Magnitude trace pane",
            () -> shell.controller().focusPane(ax.xz.mri.ui.workbench.PaneId.TRACE_MAGNITUDE)));

        steps.add(new Step("05-cross-section-focused",
            "focus the Cross-section pane",
            () -> shell.controller().focusPane(ax.xz.mri.ui.workbench.PaneId.CROSS_SECTION)));

        // ── NV diamond scenarios ─────────────────────────────────────────
        // The analysis panes (Sphere, Cross-section, Points, …) only host
        // their content when a sequence tab is active in the workspace —
        // switching to the substance tab swallows the layout. So every NV
        // analysis-pane scenario refocuses the Ramsey sequence first.
        steps.add(new Step("06-nv-ramsey-open",
            "open the NV Ramsey sequence — schematic + clip lanes for laser/MW I/MW Q",
            () -> shell.controller().openSequenceTab(ramseyDoc)));

        steps.add(new Step("07-nv-points-pane",
            "Points pane: 16 NV centres as NV_CENTRE rows, locked + italic-badged",
            () -> {
                shell.controller().openSequenceTab(ramseyDoc);
                shell.controller().focusPane(ax.xz.mri.ui.workbench.PaneId.POINTS);
            }));

        steps.add(new Step("08-nv-cross-section-placeholder",
            "Cross-section pane: 'No continuous-magnetisation substance' placeholder",
            () -> {
                shell.controller().openSequenceTab(ramseyDoc);
                shell.controller().focusPane(ax.xz.mri.ui.workbench.PaneId.CROSS_SECTION);
            }));

        steps.add(new Step("09-nv-sphere-pane",
            "Sphere pane: no NV trajectory yet (deferred to v1.1)",
            () -> {
                shell.controller().openSequenceTab(ramseyDoc);
                shell.controller().focusPane(ax.xz.mri.ui.workbench.PaneId.SPHERE);
            }));

        steps.add(new Step("10-nv-magnitude-trace",
            "Magnitude trace pane during the NV Ramsey sequence (clicks_red)",
            () -> {
                shell.controller().openSequenceTab(ramseyDoc);
                shell.controller().focusPane(ax.xz.mri.ui.workbench.PaneId.TRACE_MAGNITUDE);
            }));

        steps.add(new Step("11-nv-inspector",
            "Inspector pane — substance properties for the NV ensemble",
            () -> {
                shell.controller().openSequenceTab(ramseyDoc);
                shell.controller().focusPane(ax.xz.mri.ui.workbench.PaneId.INSPECTOR);
            }));

        steps.add(new Step("12-nv-substance-editor",
            "NV substance editor — programmatically picks the Lorentzian dipole eigenfield to show overlay arrows",
            () -> {
                shell.controller().openSubstanceTab(nvSubstance);
                javafx.application.Platform.runLater(() -> {
                    // Programmatically pick the Lorentzian dipole pair eigenfield
                    // so the snapshot captures the arrow overlay alongside the
                    // NV centres — the dropdown defaults to (none) so without
                    // this the screenshot would be empty.
                    var combo = lookupCombo(shell, "Lorentzian dipole pair");
                    if (combo == null) return;
                    for (var item : combo.getItems()) {
                        if (item != null && item.toString().contains("Lorentzian")) {
                            @SuppressWarnings({"unchecked","rawtypes"})
                            var raw = (javafx.scene.control.ComboBox) combo;
                            raw.getSelectionModel().select(item);
                            raw.getOnAction().handle(new javafx.event.ActionEvent());
                            break;
                        }
                    }
                });
            }));

        steps.add(new Step("13-low-field-sim-config",
            "open the Low-field MRI sim config — Substance section shows Water (γ, T1, T2)",
            () -> shell.controller().session().project.openNode(simConfig.id())));

        steps.add(new Step("13b-nv-sim-config-method",
            "open the NV sim config — Reference tab shows the 'NV interaction model' section (Max cluster size 3 + Coupling cutoff)",
            () -> {
                session.project.openNode(nvConfig.id());
                javafx.application.Platform.runLater(() -> {
                    var pane = (ax.xz.mri.ui.workbench.pane.SimulationConfigEditorPane) lookupNode(
                        shell, ax.xz.mri.ui.workbench.pane.SimulationConfigEditorPane.class, n -> true);
                    if (pane != null) pane.selectReferenceTab();
                });
            }));

        // Standalone snapshot of the NV-diamond wizard's interaction-model step
        // (Max cluster size defaults to 3; coupling cutoff in nm). Wrapped in a
        // themed scene so the section styling resolves.
        var nvMethodStep = SimConfigTemplate.NV_CENTRE_DIAMOND.configStep();
        var nvMethodRoot = themed(scene, (javafx.scene.Parent) nvMethodStep.content());
        var nvMethodStepPreview = new Step("13c-nv-method-wizard-step",
            "New Sim Config wizard — NV interaction-model step (Max cluster size 3 by default)",
            nvMethodStep::onEnter);
        nvMethodStepPreview.snapshotAltRoot(nvMethodRoot);
        steps.add(nvMethodStepPreview);

        steps.add(new Step("14-bloch-substance-editor",
            "open the Water substance editor — Bloch form, no 3-D viewport",
            () -> {
                var blochSub = projectState.substanceIds().stream()
                    .map(projectState::substance)
                    .filter(s -> s != null && s.substance() instanceof ax.xz.mri.model.substance.ContinuousMagnetisation)
                    .findFirst()
                    .orElseThrow();
                shell.controller().openSubstanceTab(blochSub);
            }));

        // Build the New-Procedure wizard up-front (non-modal so the preview
        // can snapshot each step without blocking).
        var wizardForPreview = ax.xz.mri.ui.wizard.NewProcedureWizard
            .buildDialog(stage, session.project);
        wizardForPreview.makeNonModalForPreview();

        var wizardStep1 = new Step("15a-wizard-step1-starter",
            "New Procedure wizard — step 1: pick a starter (5 options)",
            wizardForPreview::showNonBlockingForPreview);
        wizardStep1.snapshotAltRoot(wizardForPreview.contentForPreview());
        steps.add(wizardStep1);

        var wizardStep2 = new Step("15b-wizard-step1-adaptive-selected",
            "New Procedure wizard — step 1 with NV adaptive coherent selected",
            () -> {
                var starterList = (javafx.scene.control.ListView<?>) lookupNode(
                    wizardForPreview.contentForPreview(),
                    javafx.scene.control.ListView.class, n -> true);
                if (starterList == null) return;
                for (int i = 0; i < starterList.getItems().size(); i++) {
                    var item = starterList.getItems().get(i);
                    if (item != null && item.toString().toLowerCase().contains("adaptive coherent")) {
                        starterList.getSelectionModel().select(i);
                        starterList.scrollTo(i);
                        break;
                    }
                }
            });
        wizardStep2.snapshotAltRoot(wizardForPreview.contentForPreview());
        steps.add(wizardStep2);

        var wizardStep3 = new Step("15c-wizard-step2-name",
            "New Procedure wizard — step 2: name the procedure (pre-filled from starter)",
            () -> wizardForPreview.navigateToForPreview(1));
        wizardStep3.snapshotAltRoot(wizardForPreview.contentForPreview());
        steps.add(wizardStep3);

        steps.add(new Step("15d-wizard-finished",
            "Wizard closed; new procedure visible in the Explorer",
            wizardForPreview::closeForPreview));

        // Create + open the NV adaptive-coherent procedure to verify the
        // editor UI, then trigger a run so the live charts populate.
        var adaptiveStarter = ax.xz.mri.model.procedure.ProcedureStarterLibrary
            .byId("nv-adaptive-coherent").orElseThrow();
        var procDoc = session.project.createProcedure(
            "NV adaptive coherent",
            adaptiveStarter.source());

        steps.add(new Step("15-adaptive-procedure-opened",
            "open the NV adaptive-coherent procedure editor — source + harness + outputs",
            () -> {
                shell.controller().openProcedureTab(procDoc);
                // Re-inspect the NV simconfig so the procedure's runProcedure()
                // uses it as the simulation context (it picks the inspected one).
                session.project.openNode(nvConfig.id());
                shell.controller().openProcedureTab(procDoc);
            }));

        steps.add(new Step("16-adaptive-procedure-running",
            "trigger the procedure run — live convergence + posterior vs truth visualisations",
            () -> {
                // Inspect NV config so the procedure runs against it.
                session.project.inspector.inspectedNodeId.set(nvConfig.id());
                var runBtn = (javafx.scene.control.Button) lookupNode(shell, javafx.scene.control.Button.class,
                    btn -> "Run".equals(((javafx.scene.control.Button) btn).getText()));
                if (runBtn != null) runBtn.fire();
            }));

        steps.add(new Step("17-adaptive-procedure-converged",
            "later in the run — RMSE has dropped, posterior tracks truth",
            () -> { /* just wait for more frames so the run progresses */ }));

        chain(scene, steps, 0);
    }

    private void chain(Scene scene, List<Step> steps, int idx) {
        if (idx >= steps.size()) {
            Platform.exit();
            return;
        }
        var step = steps.get(idx);
        Platform.runLater(() -> {
            try { step.action.run(); } catch (Exception ex) { ex.printStackTrace(); }
            // Wait enough frames for: (a) the document tab to activate +
            // dispatch its simulate request, (b) the async simulator to
            // produce a CompiledSimulation + trajectories, (c) the analysis
            // panes to bind the result and redraw. 6 frames was too short to
            // catch the substance-aware Points pane repopulating with NV
            // centres after a tab switch; 60 (~1 s at 60 Hz) is plenty.
            new javafx.animation.AnimationTimer() {
                int frames = 0;
                @Override public void handle(long now) {
                    if (frames++ < 60) return;
                    stop();
                    try {
                        snapshot(scene, step.name, step.description, step.altRoot.get());
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                    chain(scene, steps, idx + 1);
                }
            }.start();
        });
    }

    private static void snapshot(Scene scene, String name, String description,
                                 javafx.scene.Parent altRoot) throws Exception {
        // For wizard / dialog steps that produce a separate Stage, snapshot
        // the altRoot the step provided rather than the studio shell scene.
        javafx.scene.Parent root = altRoot != null ? altRoot : (javafx.scene.Parent) scene.getRoot();
        root.applyCss();
        root.layout();
        var params = new SnapshotParameters();
        params.setFill(Color.web("#eff1f4"));
        WritableImage img = root.snapshot(params, null);
        var out = OUT.resolve(name + ".png").toFile();
        ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", out);
        System.out.printf("[shell-preview] %s — %s  (%dx%d)%n",
            name, description, (int) img.getWidth(), (int) img.getHeight());
    }

    private static void addStylesheet(Scene scene, java.net.URL url) {
        if (url != null) scene.getStylesheets().add(url.toExternalForm());
    }

    /**
     * Wrap a detached node in a padded container inside a throwaway Scene that
     * shares the main scene's stylesheets, so its CSS classes resolve when
     * snapshotted standalone. Returns the container to snapshot.
     */
    private static javafx.scene.Parent themed(Scene mainScene, javafx.scene.Parent node) {
        var box = new javafx.scene.layout.StackPane(node);
        box.setPadding(new javafx.geometry.Insets(16));
        box.setStyle("-fx-background-color: #eff1f4;");
        box.setPrefWidth(440);
        var s = new Scene(box);
        s.getStylesheets().addAll(mainScene.getStylesheets());
        return box;
    }

    /**
     * A scripted preview step. {@code altRoot} supplies an optional Parent
     * to snapshot in place of the studio shell scene — wizard previews use
     * this to capture their own modal-stage content.
     */
    private static final class Step {
        final String name;
        final String description;
        final Runnable action;
        final java.util.concurrent.atomic.AtomicReference<javafx.scene.Parent> altRoot =
            new java.util.concurrent.atomic.AtomicReference<>();

        Step(String name, String description, Runnable action) {
            this.name = name;
            this.description = description;
            this.action = action;
        }

        /** Set an alternate Parent to snapshot for this step. */
        Step snapshotAltRoot(javafx.scene.Parent root) {
            this.altRoot.set(root);
            return this;
        }
    }

    /**
     * Walk the scene graph to find a {@link javafx.scene.control.ComboBox} whose
     * item list contains an item with {@code marker} in its toString — used
     * by the preview to locate specific UI controls programmatically.
     */
    private static javafx.scene.control.ComboBox<?> lookupCombo(javafx.scene.Node root, String marker) {
        if (root instanceof javafx.scene.control.ComboBox<?> c) {
            for (var item : c.getItems()) {
                if (item != null && item.toString().contains(marker)) return c;
            }
        }
        if (root instanceof javafx.scene.Parent p) {
            for (var child : p.getChildrenUnmodifiable()) {
                var found = lookupCombo(child, marker);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Walk the scene graph for the first node matching {@code type} that
     * satisfies {@code predicate}. Used to locate Run/Stop/etc. buttons
     * by their label text without needing FX-CSS lookup IDs.
     */
    private static javafx.scene.Node lookupNode(
        javafx.scene.Node root,
        Class<? extends javafx.scene.Node> type,
        java.util.function.Predicate<javafx.scene.Node> predicate
    ) {
        if (type.isInstance(root) && predicate.test(root)) return root;
        if (root instanceof javafx.scene.Parent p) {
            for (var child : p.getChildrenUnmodifiable()) {
                var found = lookupNode(child, type, predicate);
                if (found != null) return found;
            }
        }
        return null;
    }
}
