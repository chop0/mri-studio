package ax.xz.mri;

import ax.xz.mri.dsl.viz.Visualisation;
import ax.xz.mri.service.procedure.ScriptHarness;
import ax.xz.mri.ui.procedure.VisualisationRenderer;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * The standalone script runner's chart window — a small JavaFX Stage
 * hosting the same {@link VisualisationRenderer.Rendered} cards the studio's
 * Procedure pane uses, with a header strip showing the script's current
 * status text + a progress bar bound to its {@code progress()} hook. Built
 * so that {@code NMRStudio.runScript} doesn't need to know anything about
 * JavaFX; it just hands {@link ScriptHarness.Tick} events to a
 * {@link Consumer} and the window does the rest.
 *
 * <p>The window mirrors {@link ax.xz.mri.ui.procedure.ProcedureEditorPane}'s
 * Outputs tab — same renderer, same in-place update path — so charts look
 * identical to what the user sees inside the studio.
 */
final class StandaloneProcedureWindow implements Consumer<ScriptHarness.Tick> {

    private final Map<String, VisualisationRenderer.Rendered> renderedById = new LinkedHashMap<>();
    private final CountDownLatch closed = new CountDownLatch(1);

    // All FX-thread-only state is created lazily inside buildAndShow() —
    // {@link javafx.stage.Stage} in particular throws if constructed off the
    // FX application thread, and the static {@link #open} entry runs the
    // factory on the main thread before delegating to the FX runtime.
    private VBox outputsBox;
    private Label statusLabel;
    private ProgressBar progressBar;
    private Label emptyHint;
    private Stage stage;

    /**
     * Open a window on the FX thread and return a {@link ScriptHarness.Tick}
     * consumer hooked to it. Initialises the JavaFX toolkit if needed —
     * users who jlink the studio image get JavaFX automatically; users who
     * launch via single-file mode without JavaFX on the module path get a
     * JavaFX startup error from this method, which {@code NMRStudio.runScript}
     * surfaces via the {@code RunOptions.headless()} fallback.
     */
    static StandaloneProcedureWindow open() {
        ensureFxStarted();
        var window = new StandaloneProcedureWindow();
        runOnFxAndWait(window::buildAndShow);
        return window;
    }

    private StandaloneProcedureWindow() {}

    private void buildAndShow() {
        outputsBox = new VBox(14);
        statusLabel = new Label("Starting…");
        progressBar = new ProgressBar(0);
        emptyHint = new Label("No outputs yet — waiting for the script to emit a Visualisation.");
        stage = new Stage();

        outputsBox.setPadding(new Insets(10, 12, 12, 12));
        outputsBox.setAlignment(Pos.TOP_CENTER);
        outputsBox.getChildren().setAll(emptyHint);
        emptyHint.setStyle("-fx-text-fill: #707070; -fx-font-size: 11.5; -fx-padding: 40 0 0 0;");
        emptyHint.setAlignment(Pos.CENTER);

        var scroll = new ScrollPane(outputsBox);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        statusLabel.setStyle("-fx-font-size: 12; -fx-text-fill: #2b2f37;");
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(statusLabel, Priority.SOMETIMES);

        progressBar.setPrefWidth(200);
        progressBar.setStyle("-fx-accent: #1a73e8;");

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var header = new HBox(12, statusLabel, spacer, progressBar);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 12, 8, 12));
        header.setStyle("-fx-background-color: #f3f5f7; -fx-border-color: #d8dde2; -fx-border-width: 0 0 1 0;");

        var root = new BorderPane();
        root.setTop(header);
        root.setCenter(scroll);

        stage.setTitle("Script output");
        var scene = new Scene(root, 720, 520);
        attachStudioStylesheet(scene);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> closed.countDown());
        stage.show();
    }

    @Override
    public void accept(ScriptHarness.Tick tick) {
        Platform.runLater(() -> apply(tick));
    }

    private void apply(ScriptHarness.Tick tick) {
        if (tick.status() != null) statusLabel.setText(tick.status());
        // null progress = "no change since last tick" → leave the bar
        // alone. NaN = indeterminate; otherwise a finite [0,1] fraction.
        if (tick.progress() != null) {
            double p = tick.progress();
            progressBar.setProgress(Double.isNaN(p) ? ProgressBar.INDETERMINATE_PROGRESS : p);
        }
        for (var viz : tick.visualisations()) addOrUpdate(viz);
    }

    private void addOrUpdate(Visualisation viz) {
        var existing = renderedById.get(viz.id());
        if (existing == null) {
            if (outputsBox.getChildren().contains(emptyHint)) outputsBox.getChildren().remove(emptyHint);
            var rendered = VisualisationRenderer.render(viz);
            renderedById.put(viz.id(), rendered);
            outputsBox.getChildren().add(rendered.node());
        } else {
            existing.update().accept(viz);
        }
    }

    /**
     * Attach the studio's main stylesheet ({@code studio.css}) so the
     * {@link VisualisationRenderer} cards render with their {@code .viz-card}
     * chart styling — proper text colours, grid lines, legend, plot
     * background. Without this the scene falls back to JavaFX's default
     * {@code modena.css} which renders chart text in {@code #898989} light
     * gray that's barely legible on macOS Retina. Studio Bento panes aren't
     * used in this window so {@code bento.css} is intentionally omitted.
     */
    private static void attachStudioStylesheet(Scene scene) {
        var url = StandaloneProcedureWindow.class.getResource("/ax/xz/mri/ui/theme/studio.css");
        if (url != null) scene.getStylesheets().add(url.toExternalForm());
    }

    /** Block the calling thread until the user closes the window. */
    void awaitClose() {
        try {
            closed.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /* ── JavaFX boot ────────────────────────────────────────────────────── */

    private static boolean fxStarted;

    private static synchronized void ensureFxStarted() {
        if (fxStarted) return;
        try {
            Platform.setImplicitExit(false);
            var latch = new CountDownLatch(1);
            Platform.startup(latch::countDown);
            latch.await();
        } catch (IllegalStateException alreadyRunning) {
            // Toolkit was initialised by something else (host app already
            // owns the FX process) — fine to proceed.
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for JavaFX startup", ie);
        }
        fxStarted = true;
    }

    private static void runOnFxAndWait(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            r.run();
            return;
        }
        var latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { r.run(); } finally { latch.countDown(); }
        });
        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
