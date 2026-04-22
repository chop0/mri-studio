package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.project.CaptureDocument;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ImportLinkDocument;
import ax.xz.mri.project.ImportedCaptureDocument;
import ax.xz.mri.project.ImportedOptimisationRunDocument;
import ax.xz.mri.project.ImportedScenarioDocument;
import ax.xz.mri.project.OptimisationConfigDocument;
import ax.xz.mri.project.OptimisationRunDocument;
import ax.xz.mri.project.ProjectNode;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectNodeKind;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.project.RunBookmarkDocument;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.project.SequenceSnapshotDocument;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.project.SimulationDocument;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import ax.xz.mri.ui.workbench.CommandId;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.ProjectDisplayNames;
import ax.xz.mri.ui.workbench.StudioIconKind;
import ax.xz.mri.ui.workbench.StudioIcons;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import ax.xz.mri.ui.workbench.pane.config.NumberField;
import ax.xz.mri.ui.workbench.pane.inspector.ClipInspectorSection;
import javafx.beans.InvalidationListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Context-sensitive right sidebar. Shows:
 * <ul>
 *   <li>Clip + sequence-editor properties when a {@link SequenceEditSession}
 *       is active. The {@link ClipInspectorSection} survives revision bumps,
 *       so editing values in a spinner doesn't rebuild the UI and won't steal
 *       focus.</li>
 *   <li>Project-node metadata when a node is selected in the explorer.</li>
 * </ul>
 */
public final class InspectorPane extends WorkbenchPane {
    private final VBox content = new VBox(10);

    /** Listeners attached to session properties during the last refresh; detached on next refresh. */
    private final List<ListenerBinding<?>> activeBindings = new ArrayList<>();

    /** The currently-alive clip section, if any. Null means no clip is selected. */
    private ClipInspectorSection activeClipSection;
    /** The edit session we're currently wired to (for detaching listeners on switch). */
    private SequenceEditSession wiredSession;

    public InspectorPane(PaneContext paneContext) {
        super(paneContext);
        setPaneTitle("Inspector");
        content.setPadding(new Insets(10));
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        setPaneContent(scroll);

        paneContext.session().project.inspector.inspectedNodeId.addListener((obs, oldValue, newValue) -> rebuild());
        paneContext.session().project.explorer.structureRevision.addListener((obs, oldValue, newValue) -> rebuild());

        paneContext.session().activeEditSession.addListener((obs, oldSession, newSession) -> rebuild());

        rebuild();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Rebuild (driven by selection / edit-session switch)
    // ══════════════════════════════════════════════════════════════════════════

    private void rebuild() {
        for (var binding : activeBindings) binding.detach();
        activeBindings.clear();
        activeClipSection = null;

        content.getChildren().clear();

        var editSession = paneContext.session().activeEditSession.get();
        if (editSession != null) {
            wireEditSession(editSession);
            content.getChildren().addAll(sequenceSection(editSession), new Separator());

            var primaryClip = editSession.primarySelectedClip();
            if (primaryClip != null) {
                activeClipSection = new ClipInspectorSection(editSession, primaryClip);
                content.getChildren().add(activeClipSection.view());
            } else {
                var empty = new Label("No clip selected.");
                empty.getStyleClass().add("cfg-row-hint");
                content.getChildren().add(empty);
            }
            return;
        }

        // Project-node inspection (explorer selection)
        var repo = paneContext.session().project.repository.get();
        ProjectNodeId nodeId = paneContext.session().project.inspector.inspectedNodeId.get();
        ProjectNode node = nodeId == null ? null : repo.node(nodeId);
        if (node == null) {
            content.getChildren().add(new Label("Select an item in the explorer to inspect it."));
            return;
        }

        content.getChildren().add(header(node));
        content.getChildren().add(new Separator());

        switch (node) {
            case ImportLinkDocument link -> populateImport(link);
            case ImportedScenarioDocument scenario -> populateImportedScenario(repo, scenario);
            case ImportedOptimisationRunDocument run -> populateRun(repo, run.name(), run.id(),
                run.firstCaptureId(), run.latestCaptureId(), run.bestCaptureId());
            case OptimisationRunDocument run -> populateRun(repo, run.name(), run.id(),
                run.firstCaptureId(), run.latestCaptureId(), run.bestCaptureId());
            case ImportedCaptureDocument capture -> populateCapture(repo, capture.name(),
                capture.sourceScenarioName(), capture.iterationKey(), capture.sequenceSnapshotId(), true);
            case CaptureDocument capture -> populateCapture(repo, capture.name(), null,
                capture.iterationKey(), capture.sequenceSnapshotId(), false);
            case SequenceSnapshotDocument snapshot -> populateSnapshot(snapshot);
            case SequenceDocument sequence -> populateSequence(sequence);
            case RunBookmarkDocument bookmark -> populateBookmark(bookmark);
            case OptimisationConfigDocument config -> {
                content.getChildren().add(infoLine("Type", "Optimisation Configuration"));
                content.getChildren().add(infoLine("Name", config.name()));
            }
            case SimulationConfigDocument simConfig -> populateSimConfig(simConfig);
            case EigenfieldDocument eigenfield -> {
                content.getChildren().add(infoLine("Type", "Eigenfield"));
                content.getChildren().add(infoLine("Name", eigenfield.name()));
                content.getChildren().add(infoLine("Description",
                    eigenfield.description() == null ? "" : eigenfield.description()));
            }
            case SimulationDocument simulation -> {
                content.getChildren().add(infoLine("Type", "Simulation"));
                content.getChildren().add(infoLine("Name", simulation.name()));
            }
            default -> content.getChildren().add(infoLine("Kind", node.kind().name()));
        }
    }

    private void wireEditSession(SequenceEditSession editSession) {
        this.wiredSession = editSession;

        // Primary clip changed → full rebuild (different clip means different fields).
        InvalidationListener primaryListener = obs -> rebuild();
        editSession.primarySelectedClipId.addListener(primaryListener);
        activeBindings.add(new ListenerBinding<>(editSession.primarySelectedClipId, primaryListener));

        // Revision bumps → refresh current clip's values in place (no rebuild).
        InvalidationListener revisionListener = obs -> {
            if (activeClipSection == null) return;
            var clip = editSession.findClip(activeClipSection.clipId());
            if (clip == null) {
                rebuild();
            } else if (activeClipSection.clipId().equals(editSession.primarySelectedClipId.get())) {
                activeClipSection.refresh();
            }
        };
        editSession.revision.addListener(revisionListener);
        activeBindings.add(new ListenerBinding<>(editSession.revision, revisionListener));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Sequence-level properties (always shown when editing)
    // ══════════════════════════════════════════════════════════════════════════

    private Node sequenceSection(SequenceEditSession editSession) {
        var box = new VBox(8);

        var title = new Label("Sequence");
        title.getStyleClass().add("clip-inspector-section");
        box.getChildren().add(title);

        // Total duration
        var duration = new NumberField().range(10, 1_000_000_000).step(100).decimals(0).unit("μs");
        duration.setValue(editSession.totalDuration.get());
        duration.valueProperty().addListener((obs, o, n) -> {
            if (n != null) editSession.setTotalDuration(n.doubleValue());
        });
        editSession.totalDuration.addListener((obs, o, n) -> duration.setValueQuiet(n.doubleValue()));
        box.getChildren().add(row("Duration", duration));

        // Snap grid
        var snap = new NumberField().range(0, 1_000_000).step(10).decimals(0).unit("μs");
        snap.setValue(editSession.snapGridSize.get());
        snap.valueProperty().addListener((obs, o, n) -> {
            if (n != null) editSession.snapGridSize.set(n.doubleValue());
        });
        editSession.snapGridSize.addListener((obs, o, n) -> snap.setValueQuiet(n.doubleValue()));
        box.getChildren().add(row("Snap grid", snap));

        // Snap toggle
        var snapToggle = new CheckBox("Snap to clip edges + grid");
        snapToggle.setSelected(editSession.snapEnabled.get());
        snapToggle.selectedProperty().bindBidirectional(editSession.snapEnabled);
        box.getChildren().add(snapToggle);

        box.getChildren().add(new Separator());

        // Simulation config selector + run controls
        var simTitle = new Label("Simulation");
        simTitle.getStyleClass().add("clip-inspector-section");
        box.getChildren().add(simTitle);

        var repo = paneContext.session().project.repository.get();
        var configs = repo.simConfigIds().stream()
            .map(id -> (SimulationConfigDocument) repo.node(id))
            .filter(java.util.Objects::nonNull)
            .toList();

        if (configs.isEmpty()) {
            var empty = new Label("No simulation configs. Create one via File menu.");
            empty.getStyleClass().add("cfg-row-hint");
            box.getChildren().add(empty);
        } else {
            var combo = new ComboBox<String>();
            combo.setPrefWidth(200);
            for (var cfg : configs) combo.getItems().add(cfg.name());

            // Pure view binding: the combo reflects editSession.activeSimConfigId.
            // When the edit session's config changes (from wizard, undo/redo, tab switch)
            // the combo tracks it; when the user picks from the combo, we push to the session.
            java.util.function.Consumer<ProjectNodeId> syncComboFromSession = id -> {
                String target = null;
                if (id != null) {
                    for (var cfg : configs) if (cfg.id().equals(id)) { target = cfg.name(); break; }
                }
                if (!java.util.Objects.equals(combo.getValue(), target)) {
                    var old = combo.getOnAction();
                    combo.setOnAction(null);
                    combo.setValue(target);
                    combo.setOnAction(old);
                }
            };
            syncComboFromSession.accept(editSession.activeSimConfigId.get());
            InvalidationListener cfgListener = obs -> syncComboFromSession.accept(editSession.activeSimConfigId.get());
            editSession.activeSimConfigId.addListener(cfgListener);
            activeBindings.add(new ListenerBinding<>(editSession.activeSimConfigId, cfgListener));

            combo.setOnAction(e -> {
                var selected = combo.getValue();
                if (selected == null) return;
                for (var cfg : configs) {
                    if (cfg.name().equals(selected)) {
                        editSession.setActiveSimConfig(cfg.id());
                        break;
                    }
                }
            });

            var simSession = findSimSessionForEditor(editSession);
            var runBtn = new Button("\u25B6 Run");
            runBtn.setOnAction(e -> { if (simSession != null) simSession.simulate(); });
            var autoToggle = new CheckBox("Auto-simulate");
            if (simSession != null) autoToggle.selectedProperty().bindBidirectional(simSession.autoSimulate);

            box.getChildren().addAll(
                row("Config", combo),
                new HBox(8, runBtn, autoToggle) {{
                    setAlignment(Pos.CENTER_LEFT);
                }}
            );
        }

        return box;
    }

    private ax.xz.mri.ui.viewmodel.SequenceSimulationSession findSimSessionForEditor(SequenceEditSession editSession) {
        return paneContext.controller().allSimSessions().stream()
            .filter(s -> s.editSession == editSession)
            .findFirst()
            .orElse(null);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Project-node inspection (unchanged behaviour, just consolidated here)
    // ══════════════════════════════════════════════════════════════════════════

    private void populateImport(ImportLinkDocument link) {
        content.getChildren().add(infoLine("Source", link.sourcePath()));
        content.getChildren().add(infoLine("Reload", link.reloadMode().name()));
        content.getChildren().add(actionRow(
            button("Reload Import", () -> paneContext.controller().commandRegistry().execute(CommandId.RELOAD_FILE))
        ));
    }

    private void populateImportedScenario(ProjectRepository repository, ImportedScenarioDocument scenario) {
        if (scenario.iterative() && scenario.importedRunId() != null) {
            content.getChildren().add(infoLine("Scenario", scenario.sourceScenarioName()));
            content.getChildren().add(infoLine("Iterations", Integer.toString(scenario.iterationKeys().size())));
            var run = (ImportedOptimisationRunDocument) repository.node(scenario.importedRunId());
            populateRun(repository, run.name(), run.id(), run.firstCaptureId(), run.latestCaptureId(), run.bestCaptureId());
        } else if (!scenario.directCaptureIds().isEmpty()) {
            var capture = repository.resolveCapture(scenario.directCaptureIds().getFirst());
            if (capture != null) {
                content.getChildren().add(infoLine("Capture", capture.name()));
                content.getChildren().add(infoLine("Iteration", capture.iterationKey()));
            }
            content.getChildren().add(actionRow(
                button("Open Capture", () -> paneContext.session().project.openNode(scenario.directCaptureIds().getFirst())),
                button("Promote to Sequence", () -> paneContext.controller().commandRegistry().execute(CommandId.PROMOTE_SNAPSHOT_TO_SEQUENCE))
            ));
        }
    }

    private void populateRun(ProjectRepository repository, String name, ProjectNodeId runId,
                             ProjectNodeId firstCaptureId, ProjectNodeId latestCaptureId, ProjectNodeId bestCaptureId) {
        var navigation = paneContext.session().project.runNavigation;
        var captureIds = repository.captureIdsForRun(runId);
        content.getChildren().add(infoLine("Optimisation", name));
        content.getChildren().add(infoLine("Captures", Integer.toString(captureIds.size())));
        if (captureIds.isEmpty()) return;

        var slider = new Slider(0, Math.max(0, captureIds.size() - 1), Math.max(0, navigation.activeCaptureIndex.get()));
        slider.setShowTickMarks(true);
        slider.setSnapToTicks(true);
        slider.setMajorTickUnit(1);
        slider.setBlockIncrement(1);
        slider.setFocusTraversable(true);
        slider.valueProperty().addListener((obs, oldValue, newValue) -> {
            int index = Math.max(0, Math.min((int) Math.round(newValue.doubleValue()), captureIds.size() - 1));
            var captureId = captureIds.get(index);
            if (!captureId.equals(navigation.activeCaptureId.get())) {
                paneContext.session().project.seekRunCapture(captureId);
            }
        });

        InvalidationListener indexListener = obs -> {
            double desired = Math.max(0, Math.min(navigation.activeCaptureIndex.get(), captureIds.size() - 1));
            if (Math.abs(slider.getValue() - desired) > 1e-9) slider.setValue(desired);
        };
        navigation.activeCaptureIndex.addListener(indexListener);
        activeBindings.add(new ListenerBinding<>(navigation.activeCaptureIndex, indexListener));

        var iterationLabel = new Label();
        InvalidationListener iterLabelListener = obs -> {
            int index = navigation.activeCaptureIndex.get();
            if (index < 0 || index >= captureIds.size()) {
                iterationLabel.setText("\u2014");
                return;
            }
            var capture = repository.resolveCapture(captureIds.get(index));
            iterationLabel.setText(capture == null || capture.iterationKey() == null ? "\u2014" : capture.iterationKey());
        };
        iterLabelListener.invalidated(null);
        navigation.activeCaptureIndex.addListener(iterLabelListener);
        activeBindings.add(new ListenerBinding<>(navigation.activeCaptureIndex, iterLabelListener));

        content.getChildren().add(section("Iteration", slider, infoLine("Key", iterationLabel)));

        var activeCaptureLabel = new Label();
        InvalidationListener captureListener = obs -> {
            var capture = paneContext.session().project.activeCapture.activeCapture.get();
            activeCaptureLabel.setText(capture == null ? "\u2014" : capture.name());
        };
        captureListener.invalidated(null);
        paneContext.session().project.activeCapture.activeCapture.addListener(captureListener);
        activeBindings.add(new ListenerBinding<>(paneContext.session().project.activeCapture.activeCapture, captureListener));
        content.getChildren().add(infoLine("Active Capture", activeCaptureLabel));

        var iterationKeyLabel = new Label();
        InvalidationListener iterKeyListener = obs -> {
            var capture = paneContext.session().project.activeCapture.activeCapture.get();
            iterationKeyLabel.setText(capture == null || capture.iterationKey() == null ? "\u2014" : capture.iterationKey());
        };
        iterKeyListener.invalidated(null);
        paneContext.session().project.activeCapture.activeCapture.addListener(iterKeyListener);
        activeBindings.add(new ListenerBinding<>(paneContext.session().project.activeCapture.activeCapture, iterKeyListener));
        content.getChildren().add(infoLine("Iteration Key", iterationKeyLabel));

        var actions = new ArrayList<Node>();
        if (firstCaptureId != null) actions.add(button("First Iteration", () -> paneContext.session().project.seekRunCapture(firstCaptureId)));
        if (latestCaptureId != null) actions.add(button("Last Iteration", () -> paneContext.session().project.seekRunCapture(latestCaptureId)));
        if (bestCaptureId != null) actions.add(button("Best Iteration", () -> paneContext.session().project.seekRunCapture(bestCaptureId)));
        if (!actions.isEmpty()) content.getChildren().add(actionRow(actions.toArray(Node[]::new)));

        content.getChildren().add(actionRow(
            button("Promote to Sequence", () -> paneContext.controller().commandRegistry().execute(CommandId.PROMOTE_SNAPSHOT_TO_SEQUENCE))
        ));
    }

    private void populateCapture(ProjectRepository repository, String name, String scenarioName,
                                 String iterationKey, ProjectNodeId snapshotId, boolean imported) {
        content.getChildren().add(infoLine("Capture", name));
        if (scenarioName != null) content.getChildren().add(infoLine("Scenario", scenarioName));
        if (iterationKey != null) content.getChildren().add(infoLine("Iteration", iterationKey));
        content.getChildren().add(infoLine("Origin", imported ? "Imported" : "Project"));
        content.getChildren().add(actionRow(
            button("Promote to Sequence", () -> paneContext.controller().commandRegistry().execute(CommandId.PROMOTE_SNAPSHOT_TO_SEQUENCE))
        ));
        var snapshot = repository.resolveSnapshot(snapshotId, null);
        if (snapshot != null) {
            content.getChildren().add(infoLine("Segments", Integer.toString(snapshot.segments().size())));
            content.getChildren().add(infoLine("Pulse Segments", Integer.toString(snapshot.pulse().size())));
        }
    }

    private void populateSnapshot(SequenceSnapshotDocument snapshot) {
        content.getChildren().add(infoLine("Kind", "Sequence Snapshot"));
        content.getChildren().add(infoLine("Segments", Integer.toString(snapshot.segments().size())));
        content.getChildren().add(infoLine("Pulse Segments", Integer.toString(snapshot.pulse().size())));
        content.getChildren().add(actionRow(
            button("Promote to Sequence", () -> paneContext.controller().commandRegistry().execute(CommandId.PROMOTE_SNAPSHOT_TO_SEQUENCE))
        ));
    }

    private void populateSequence(SequenceDocument sequence) {
        content.getChildren().add(infoLine("Kind", "Sequence"));
        content.getChildren().add(infoLine("Segments", Integer.toString(sequence.segments().size())));
        content.getChildren().add(infoLine("Pulse Segments", Integer.toString(sequence.pulse().size())));
        content.getChildren().add(actionRow(
            button("Rename", () -> renameSequence(sequence.id())),
            button("Delete", () -> {
                paneContext.session().project.selectNode(sequence.id());
                paneContext.controller().commandRegistry().execute(CommandId.DELETE_SEQUENCE);
            })
        ));
    }

    private void populateSimConfig(SimulationConfigDocument simConfig) {
        content.getChildren().add(infoLine("Type", "Simulation Config"));
        content.getChildren().add(infoLine("Name", simConfig.name()));
        var cfg = simConfig.config();
        if (cfg != null) {
            content.getChildren().add(infoLine("B₀ ref", String.format("%.4f T", cfg.referenceB0Tesla())));
            content.getChildren().add(infoLine("T₁", String.format("%.0f ms", cfg.t1Ms())));
            content.getChildren().add(infoLine("T₂", String.format("%.0f ms", cfg.t2Ms())));
            content.getChildren().add(infoLine("Fields", String.valueOf(cfg.fields().size())));
        }
        content.getChildren().add(new Separator());
        content.getChildren().add(actionRow(
            button("Open Editor", () -> paneContext.session().project.openNode(simConfig.id())),
            button("Delete", () -> paneContext.session().project.deleteSimConfig(simConfig.id()))
        ));
    }

    private void populateBookmark(RunBookmarkDocument bookmark) {
        content.getChildren().add(infoLine("Bookmark", bookmark.name()));
        content.getChildren().add(infoLine("Iteration", bookmark.iterationKey()));
        content.getChildren().add(actionRow(
            button("Open Run", () -> paneContext.session().project.openNode(bookmark.id()))
        ));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UI helpers (shared with project-node inspection)
    // ══════════════════════════════════════════════════════════════════════════

    private Node header(ProjectNode node) {
        var icon = switch (node.kind()) {
            case ProjectNodeKind.IMPORT_LINK -> StudioIcons.create(StudioIconKind.IMPORT);
            case ProjectNodeKind.IMPORTED_SCENARIO -> StudioIcons.create(
                node instanceof ImportedScenarioDocument scenario && scenario.iterative()
                    ? StudioIconKind.RUN
                    : StudioIconKind.SCENARIO
            );
            case ProjectNodeKind.IMPORTED_OPTIMISATION_RUN, ProjectNodeKind.OPTIMISATION_RUN ->
                StudioIcons.create(StudioIconKind.RUN);
            case ProjectNodeKind.IMPORTED_CAPTURE, ProjectNodeKind.CAPTURE ->
                StudioIcons.create(StudioIconKind.CAPTURE);
            case ProjectNodeKind.SEQUENCE_SNAPSHOT -> StudioIcons.create(StudioIconKind.SNAPSHOT);
            case ProjectNodeKind.SEQUENCE -> StudioIcons.create(StudioIconKind.SEQUENCE);
            case ProjectNodeKind.SIMULATION -> StudioIcons.create(StudioIconKind.SIMULATION);
            case ProjectNodeKind.OPTIMISATION_CONFIG -> StudioIcons.create(StudioIconKind.OPTIMISATION_CONFIG);
            case ProjectNodeKind.RUN_BOOKMARK -> StudioIcons.create(StudioIconKind.BOOKMARK);
            case ProjectNodeKind.EIGENFIELD -> StudioIcons.create(StudioIconKind.EIGENFIELD);
        };
        String titleText = node instanceof ImportedScenarioDocument scenario && scenario.iterative()
            ? "Optimisation: " + scenario.name()
            : ProjectDisplayNames.label(node);
        var title = new Label(titleText);
        title.getStyleClass().add("inspector-title");
        var row = new HBox(8, icon, title);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Node infoLine(String label, String value) {
        var name = new Label(label + ":");
        name.getStyleClass().add("inspector-key");
        var body = new Label(value == null ? "\u2014" : value);
        body.setWrapText(true);
        return new VBox(2, name, body);
    }

    private Node infoLine(String label, Label body) {
        var name = new Label(label + ":");
        name.getStyleClass().add("inspector-key");
        body.setWrapText(true);
        return new VBox(2, name, body);
    }

    private Node section(String title, Node... children) {
        var label = new Label(title);
        label.getStyleClass().add("inspector-section-title");
        var box = new VBox(6);
        box.getChildren().add(label);
        box.getChildren().addAll(children);
        return box;
    }

    private Node actionRow(Node... children) {
        var row = new HBox(8);
        row.getChildren().addAll(children);
        return row;
    }

    private Node row(String label, Node field) {
        var lbl = new Label(label);
        lbl.getStyleClass().add("clip-inspector-label");
        lbl.setMinWidth(90);
        lbl.setPrefWidth(90);
        var row = new HBox(6, lbl, field);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Button button(String text, Runnable action) {
        var button = new Button(text);
        button.setOnAction(event -> action.run());
        return button;
    }

    private void renameSequence(ProjectNodeId sequenceId) {
        var repository = paneContext.session().project.repository.get();
        var node = repository.node(sequenceId);
        if (!(node instanceof SequenceDocument sequence)) return;
        var dialog = new TextInputDialog(sequence.name());
        dialog.setTitle("Rename Sequence");
        dialog.setHeaderText("Rename sequence");
        dialog.setContentText("Name:");
        dialog.showAndWait().map(String::trim).filter(value -> !value.isBlank()).ifPresent(value ->
            paneContext.session().project.renameSequence(sequenceId, value));
    }

    /** Tracks a listener attached to an observable so it can be cleanly detached later. */
    private record ListenerBinding<T extends javafx.beans.Observable>(T observable, InvalidationListener listener) {
        void detach() { observable.removeListener(listener); }
    }
}
