package ax.xz.mri.ui.preview;

import ax.xz.mri.model.simulation.PhysicsParams;
import ax.xz.mri.ui.edit.EditSession;
import ax.xz.mri.ui.pane.SequenceEditorPane;
import ax.xz.mri.ui.viewmodel.StudioSession;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.PaneId;
import ax.xz.mri.ui.workbench.WorkbenchController;
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

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives the new {@link SequenceEditorPane} through realistic interactions
 * (sim config + CPMG sequence built from {@link SequenceStarterLibrary},
 * selection, drag, resize, scrub, zoom, output toggle, inspector edits) and
 * snapshots the scene at each step. Output goes to {@code build/ui-preview/}.
 *
 * <p>Each step is a small lambda that mutates state and labels its own
 * snapshot; the runner chains them via {@code Platform.runLater} so JavaFX
 * gets a layout pulse between steps.
 */
public final class UiPreview extends Application {
    private static final Path OUT = Path.of("build", "ui-preview");

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        Files.createDirectories(OUT);
        for (var existing : Files.newDirectoryStream(OUT, "*.png")) Files.deleteIfExists(existing);

        // ── Real project pipeline: sim config + CPMG sequence ──────────
        var session = new StudioSession();
        var simConfig = session.project.createSimConfig(
            "Low-field MRI", SimConfigTemplate.LOW_FIELD_MRI, PhysicsParams.DEFAULTS);
        var seqDoc = session.project.createSequenceFromStarter(
            "CPMG", simConfig.id(),
            SequenceStarterLibrary.byId("cpmg").orElseThrow());

        var controller = new WorkbenchController(session);
        var paneContext = new PaneContext(session, controller, PaneId.SEQUENCE_EDITOR);
        var editorPane = new SequenceEditorPane(paneContext);
        editorPane.open(seqDoc);
        // Wire the dispatcher so the OutputBand has live trace data flowing.
        var simSession = session.newSimDispatcher(editorPane.editSession());
        editorPane.editSession().activeConfigDoc.set(simConfig);
        editorPane.wireSimSession(simSession);

        var scene = new Scene(editorPane, 1440, 820);
        scene.setFill(Color.web("#eff1f4"));
        scene.getStylesheets().add(getClass().getResource("/ax/xz/mri/ui/theme/studio.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("MRI Studio – UI Preview");
        stage.show();

        // Wait for the sim to finish before capturing snapshots so the
        // OutputBand's probe rows have actual trace data, not empty rows.
        var es = editorPane.editSession();
        if (es.lastSimulationTraces.get() != null) {
            runScenarios(scene, editorPane);
        } else {
            es.lastSimulationTraces.addListener((obs, o, n) -> {
                if (n != null) Platform.runLater(() -> runScenarios(scene, editorPane));
            });
            // Fallback: don't hang forever if sim never completes.
            new javafx.animation.AnimationTimer() {
                int frames = 0;
                @Override public void handle(long now) {
                    if (frames++ > 240 || es.lastSimulationTraces.get() != null) {
                        stop();
                        if (es.lastSimulationTraces.get() == null) {
                            System.out.println("[ui-preview] WARNING: sim did not complete within 4s — proceeding anyway");
                            runScenarios(scene, editorPane);
                        }
                    }
                }
            }.start();
        }
    }

    // ── Scenario runner ────────────────────────────────────────────────

    private void runScenarios(Scene scene, SequenceEditorPane editorPane) {
        var es = editorPane.editSession();
        var ta = es.timeAxis();
        var steps = new ArrayList<Step>();

        steps.add(new Step("01-default",
            "fresh CPMG-built sequence, no selection", () -> {}));

        steps.add(new Step("02-clip-selected",
            "primary-select first clip (orange border)",
            () -> { if (!es.clips.isEmpty()) es.selection.selectOnly(es.clips.getFirst().id()); }));

        steps.add(new Step("03-multi-selected",
            "shift-add a second clip to selection",
            () -> { if (es.clips.size() > 1) es.selection.add(es.clips.get(1).id()); }));

        steps.add(new Step("04-zoomed-in",
            "zoom around middle of sequence",
            () -> ta.viewport.zoomAround(ta.domain.maxTime() * 0.4, 0.4)));

        steps.add(new Step("05-cursor-mid",
            "cursor moved to viewport centre",
            () -> ta.cursor.time.set(ta.viewport.start.get() + ta.viewport.span() * 0.5)));

        steps.add(new Step("06-analysis-window",
            "analysis window narrowed around the cursor",
            () -> {
                double c = ta.cursor.time.get();
                ta.analysis.start.set(c - ta.viewport.span() * 0.15);
                ta.analysis.end.set(c + ta.viewport.span() * 0.15);
            }));

        steps.add(new Step("07-snap-preview",
            "snap chip visible mid-drag",
            () -> {
                if (es.clips.isEmpty()) return;
                var first = es.clips.getFirst();
                es.preview.active.set(ax.xz.mri.ui.edit.EditPreview.GestureKind.MOVE_CLIP);
                es.preview.snapTargetMicros.set(first.endTime());
                es.preview.draggingClipIds.add(first.id());
            }));

        steps.add(new Step("08-snap-cleared",
            "snap chip removed once drag ends",
            () -> {
                es.preview.active.set(null);
                es.preview.snapTargetMicros.set(Double.NaN);
                es.preview.draggingClipIds.clear();
            }));

        steps.add(new Step("09-amplitude-bumped",
            "amplitude increased on second clip — primary stays distinct",
            () -> {
                if (es.clips.size() < 2) return;
                var clip = es.clips.get(1);
                es.setClipAmplitude(clip.id(), clip.amplitude() * 1.6);
            }));

        steps.add(new Step("10-stay-centred-resized",
            "centred resize: drag right edge inward (left mirrors)",
            () -> {
                if (es.clips.isEmpty()) return;
                var clip = es.clips.getFirst();
                es.resizeClipRight(clip.id(), clip.endTime() - clip.duration() * 0.25);
            }));

        steps.add(new Step("11-zoom-out",
            "zoom out to fit",
            () -> ta.viewport.fit()));

        steps.add(new Step("12-track-collapsed",
            "first track collapsed",
            () -> {
                if (!es.tracks.isEmpty()) {
                    es.setTrackCollapsed(es.tracks.getFirst().id(), true);
                }
            }));

        steps.add(new Step("13-track-uncollapsed",
            "first track uncollapsed again",
            () -> {
                if (!es.tracks.isEmpty()) {
                    es.setTrackCollapsed(es.tracks.getFirst().id(), false);
                }
            }));

        steps.add(new Step("14-output-toggled",
            "all sim-output probes enabled",
            () -> {
                var circuit = es.activeCircuit();
                if (circuit != null) {
                    for (var probe : circuit.probes()) es.enabledSimOutputs.add(probe.name());
                }
            }));

        steps.add(new Step("15-clip-deleted",
            "delete the primary-selected clip",
            () -> {
                var pid = es.selection.primary().get();
                if (pid != null) es.removeClip(pid);
            }));

        chain(scene, steps, 0);
    }

    private void chain(Scene scene, List<Step> steps, int idx) {
        if (idx >= steps.size()) {
            Platform.exit();
            return;
        }
        var step = steps.get(idx);
        Platform.runLater(() -> {
            step.action.run();
            // Wait several frames for layout, binding propagation, and the
            // Canvas-backed elements (ViewportMiniStrip, DawScrubStrip) to repaint.
            // A single Platform.runLater wasn't enough — bindings stale, ticks
            // out of date. AnimationTimer guarantees we run after a real
            // render pulse.
            new javafx.animation.AnimationTimer() {
                int frames = 0;
                @Override public void handle(long now) {
                    if (frames++ < 4) return;
                    stop();
                    try {
                        snapshot(scene, step.name, step.description);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                    chain(scene, steps, idx + 1);
                }
            }.start();
        });
    }

    private static void snapshot(Scene scene, String name, String description) throws Exception {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        var params = new SnapshotParameters();
        params.setFill(Color.web("#eff1f4"));
        WritableImage img = scene.getRoot().snapshot(params, null);
        var out = OUT.resolve(name + ".png").toFile();
        ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", out);
        var pane = (SequenceEditorPane) scene.getRoot();
        var ta = pane.editSession().timeAxis();
        var lane = scene.getRoot().lookup(".track-lane");
        var laneStackHost = scene.getRoot().lookup(".lane-stack-host");
        var clips = scene.getRoot().lookupAll(".clip").stream()
            .filter(n -> n instanceof javafx.scene.layout.Region)
            .map(n -> (javafx.scene.layout.Region) n)
            .toList();
        var clipsState = new StringBuilder("clips=[");
        for (int i = 0; i < Math.min(clips.size(), 5); i++) {
            var c = clips.get(i);
            clipsState.append(String.format(" #%d t=%.0fμs lx=%.0f w=%.0f",
                i,
                pane.editSession().clips.get(i).startTime(),
                c.getLayoutX(), c.getWidth()));
        }
        clipsState.append(" ]");
        String laneW = lane == null ? "?" : String.format("%.0f", ((javafx.scene.layout.Region) lane).getWidth());
        String hostW = laneStackHost == null ? "?" : String.format("%.0f", ((javafx.scene.layout.Region) laneStackHost).getWidth());
        System.out.printf("[ui-preview] %s — %s%n  (max=%.0f vp=[%.0f, %.0f] cur=%.0f an=[%.0f, %.0f] lane=%s host=%s %s)%n",
            name, description,
            ta.domain.maxTime.get(),
            ta.viewport.start.get(), ta.viewport.end.get(),
            ta.cursor.time.get(),
            ta.analysis.start.get(), ta.analysis.end.get(),
            laneW, hostW,
            clipsState);
    }

    private record Step(String name, String description, Runnable action) {}
}
