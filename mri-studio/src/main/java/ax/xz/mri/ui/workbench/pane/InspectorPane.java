package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.sequence.ClipShape;
import ax.xz.mri.model.sequence.SignalChannel;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.project.CaptureDocument;
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
import ax.xz.mri.project.SimulationDocument;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import ax.xz.mri.ui.workbench.CommandId;
import ax.xz.mri.ui.workbench.PaneContext;
import ax.xz.mri.ui.workbench.ProjectDisplayNames;
import ax.xz.mri.ui.workbench.StudioIconKind;
import ax.xz.mri.ui.workbench.StudioIcons;
import ax.xz.mri.ui.workbench.framework.WorkbenchPane;
import javafx.beans.InvalidationListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.GridPane;

import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/** Context-sensitive right sidebar for metadata, run scrubbing, and object actions. */
public final class InspectorPane extends WorkbenchPane {
    private final VBox content = new VBox(10);

    /** Listeners attached to session properties during the last refresh; detached on next refresh. */
    private final List<ListenerBinding<?>> activeBindings = new ArrayList<>();

    public InspectorPane(PaneContext paneContext) {
        super(paneContext);
        setPaneTitle("Inspector");
        content.setPadding(new Insets(10));
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        setPaneContent(scroll);

        paneContext.session().project.inspector.inspectedNodeId.addListener((obs, oldValue, newValue) -> refresh());
        paneContext.session().project.explorer.structureRevision.addListener((obs, oldValue, newValue) -> refresh());

        // Listen for sequence editing session changes (clip selection, edits)
        paneContext.session().activeEditSession.addListener((obs, oldSession, newSession) -> {
            if (oldSession != null) {
                // Listeners will be cleaned up via activeBindings on next refresh
            }
            refresh();
        });

        refresh();
    }

    private boolean suppressRefresh;

    private void refresh() {
        if (suppressRefresh) return;
        // Detach listeners from the previous refresh cycle to prevent leaks.
        for (var binding : activeBindings) binding.detach();
        activeBindings.clear();

        content.getChildren().clear();

        // If in sequence editing mode, show sequence properties + optional clip properties
        var editSession = paneContext.session().activeEditSession.get();
        if (editSession != null) {
            InvalidationListener clipListener = obs -> refresh();
            editSession.primarySelectedClipId.addListener(clipListener);
            activeBindings.add(new ListenerBinding<>(editSession.primarySelectedClipId, clipListener));
            editSession.revision.addListener(clipListener);
            activeBindings.add(new ListenerBinding<>(editSession.revision, clipListener));

            // Sequence-level properties
            populateSequenceEditorProperties(editSession);

            // Clip properties (if a clip is selected)
            var primaryClip = editSession.primarySelectedClip();
            if (primaryClip != null) {
                content.getChildren().add(new Separator());
                populateClipProperties(editSession, primaryClip);
            }
            return;
        }

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
            case ImportedOptimisationRunDocument run -> populateRun(repo, run.name(), run.id(), run.firstCaptureId(), run.latestCaptureId(), run.bestCaptureId());
            case OptimisationRunDocument run -> populateRun(repo, run.name(), run.id(), run.firstCaptureId(), run.latestCaptureId(), run.bestCaptureId());
            case ImportedCaptureDocument capture -> populateCapture(repo, capture.name(), capture.sourceScenarioName(), capture.iterationKey(), capture.sequenceSnapshotId(), true);
            case CaptureDocument capture -> populateCapture(repo, capture.name(), null, capture.iterationKey(), capture.sequenceSnapshotId(), false);
            case SequenceSnapshotDocument snapshot -> populateSnapshot(snapshot);
            case SequenceDocument sequence -> populateSequence(sequence);
            case RunBookmarkDocument bookmark -> populateBookmark(bookmark);
            case OptimisationConfigDocument config -> {
                content.getChildren().add(infoLine("Type", "Optimisation Configuration"));
                content.getChildren().add(infoLine("Name", config.name()));
            }
            case ax.xz.mri.project.SimulationConfigDocument simConfig -> {
                content.getChildren().add(infoLine("Type", "Simulation Config"));
                content.getChildren().add(infoLine("Name", simConfig.name()));
                var cfg = simConfig.config();
                if (cfg != null) {
                    content.getChildren().add(infoLine("B₀", String.format("%.1f T", cfg.b0Tesla())));
                    content.getChildren().add(infoLine("T₁", String.format("%.0f ms", cfg.t1Ms())));
                    content.getChildren().add(infoLine("T₂", String.format("%.0f ms", cfg.t2Ms())));
                    content.getChildren().add(infoLine("Preset", cfg.preset().displayName()));
                }
                content.getChildren().add(new Separator());
                content.getChildren().add(actionRow(
                    button("Delete", () -> paneContext.session().project.deleteSimConfig(simConfig.id()))
                ));
            }
            case SimulationDocument simulation -> {
                content.getChildren().add(infoLine("Type", "Simulation"));
                content.getChildren().add(infoLine("Name", simulation.name()));
            }
            default -> content.getChildren().add(infoLine("Kind", node.kind().name()));
        }
    }

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

    private void populateRun(ProjectRepository repository, String name, ProjectNodeId runId, ProjectNodeId firstCaptureId,
                             ProjectNodeId latestCaptureId, ProjectNodeId bestCaptureId) {
        var navigation = paneContext.session().project.runNavigation;
        var captureIds = repository.captureIdsForRun(runId);
        content.getChildren().add(infoLine("Optimisation", name));
        content.getChildren().add(infoLine("Captures", Integer.toString(captureIds.size())));
        if (captureIds.isEmpty()) return;

        var slider = new Slider(0, Math.max(0, captureIds.size() - 1), Math.max(0, navigation.activeCaptureIndex.get()));
        slider.setShowTickMarks(true);
        slider.setShowTickLabels(false);
        slider.setSnapToTicks(true);
        slider.setMajorTickUnit(1);
        slider.setMinorTickCount(0);
        slider.setBlockIncrement(1);
        slider.setFocusTraversable(true);
        slider.valueProperty().addListener((obs, oldValue, newValue) -> {
            int index = Math.max(0, Math.min((int) Math.round(newValue.doubleValue()), captureIds.size() - 1));
            var captureId = captureIds.get(index);
            if (!captureId.equals(navigation.activeCaptureId.get())) {
                paneContext.session().project.seekRunCapture(captureId);
            }
        });

        // Bind slider to the run navigation index — track this listener for cleanup.
        InvalidationListener indexListener = obs -> {
            double desired = Math.max(0, Math.min(navigation.activeCaptureIndex.get(), captureIds.size() - 1));
            if (Math.abs(slider.getValue() - desired) > 1e-9) {
                slider.setValue(desired);
            }
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
        iterLabelListener.invalidated(null); // initial value
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

    private void populateCapture(ProjectRepository repository, String name, String scenarioName, String iterationKey,
                                 ProjectNodeId snapshotId, boolean imported) {
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

    /** Sequence-level properties: duration, snap grid, sim config selector. */
    private void populateSequenceEditorProperties(SequenceEditSession editSession) {
        content.getChildren().add(section("Sequence"));

        var grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(4);
        int row = 0;

        // Duration
        row = addInspectorField(grid, row, "Duration (μs)", editSession.totalDuration.get(),
            10, 100000, 100,
            v -> { suppressRefresh = true; try { editSession.setTotalDuration(v); } finally { suppressRefresh = false; } });

        // Snap grid size
        row = addInspectorField(grid, row, "Snap Grid (μs)", editSession.snapGridSize.get(),
            0, 1000, 10,
            v -> { suppressRefresh = true; try { editSession.snapGridSize.set(v); } finally { suppressRefresh = false; } });

        content.getChildren().add(grid);

        // Simulation config selector
        content.getChildren().add(new Separator());
        content.getChildren().add(section("Simulation"));

        var repo = paneContext.session().project.repository.get();
        var configs = repo.simConfigIds().stream()
            .map(id -> (ax.xz.mri.project.SimulationConfigDocument) repo.node(id))
            .filter(java.util.Objects::nonNull)
            .toList();

        if (configs.isEmpty()) {
            content.getChildren().add(new Label("No simulation configs. Create one via File menu."));
        } else {
            var configCombo = new ComboBox<String>();
            configCombo.setStyle("-fx-font-size: 10;");
            configCombo.setPrefWidth(180);
            for (var cfg : configs) configCombo.getItems().add(cfg.name());

            // Find the active sim session for this editor
            var simSession = findSimSessionForEditor(editSession);
            if (simSession != null && simSession.activeConfigDoc.get() != null) {
                configCombo.setValue(simSession.activeConfigDoc.get().name());
            } else if (!configs.isEmpty()) {
                configCombo.setValue(configs.getFirst().name());
            }

            configCombo.setOnAction(e -> {
                var selected = configCombo.getValue();
                if (selected == null || simSession == null) return;
                for (var cfg : configs) {
                    if (cfg.name().equals(selected)) {
                        simSession.loadConfig(cfg);
                        break;
                    }
                }
            });

            var simRunBtn = new Button("\u25b6 Run");
            simRunBtn.setStyle("-fx-font-size: 10;");
            simRunBtn.setOnAction(e -> { if (simSession != null) simSession.simulate(); });

            var autoToggle = new javafx.scene.control.CheckBox("Auto");
            autoToggle.setStyle("-fx-font-size: 10;");
            if (simSession != null) autoToggle.selectedProperty().bindBidirectional(simSession.autoSimulate);

            content.getChildren().addAll(
                new HBox(6, new Label("Config:"), configCombo),
                new HBox(6, simRunBtn, autoToggle)
            );
        }
    }

    /** Find the sim session for a given edit session (via the open sim sessions map). */
    private ax.xz.mri.ui.viewmodel.SequenceSimulationSession findSimSessionForEditor(SequenceEditSession editSession) {
        // The edit session's original document has the sequence ID
        var doc = editSession.originalDocument.get();
        if (doc == null) return null;
        // Walk the controller's open sim sessions (accessible via session)
        // For simplicity, check the active edit session's sim session
        // The controller stores them by seqId
        return paneContext.controller().getSimSessionForSequence(doc.id().value());
    }

    private void populateClipProperties(SequenceEditSession editSession, SignalClip clip) {
        int selCount = editSession.selectedClipIds.size();

        // Header
        var channelLabel = new Label(clip.channel().label());
        channelLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12;");
        var shapeCombo = new ComboBox<ClipShape>();
        shapeCombo.getItems().addAll(ClipShape.values());
        shapeCombo.setValue(clip.shape());
        shapeCombo.setStyle("-fx-font-size: 11;");
        shapeCombo.setOnAction(e -> {
            var selected = shapeCombo.getValue();
            if (selected != null) {
                suppressRefresh = true;
                try { editSession.setClipShape(clip.id(), selected); }
                finally { suppressRefresh = false; }
            }
        });
        content.getChildren().addAll(
            new HBox(8, channelLabel, new Label("→"), shapeCombo),
            new Separator()
        );

        if (selCount > 1) {
            content.getChildren().add(new Label(selCount + " clips selected"));
            content.getChildren().add(new Separator());
        }

        // Properties grid
        var grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(4);
        int row = 0;

        row = addInspectorField(grid, row, "Start (μs)", clip.startTime(), 0, editSession.totalDuration.get(), 1,
            v -> { suppressRefresh = true; try { editSession.moveClip(clip.id(), v); } finally { suppressRefresh = false; } });
        row = addInspectorField(grid, row, "Duration (μs)", clip.duration(), editSession.dt.get(), editSession.totalDuration.get(), 1,
            v -> { suppressRefresh = true; try { editSession.resizeClip(clip.id(), v); } finally { suppressRefresh = false; } });
        row = addSiField(grid, row, "Amplitude", clip.amplitude(), clip.channel(),
            v -> { suppressRefresh = true; try { editSession.setClipAmplitude(clip.id(), v); } finally { suppressRefresh = false; } });

        // Shape-specific params
        switch (clip.shape()) {
            case SINC -> {
                row = addInspectorField(grid, row, "Bandwidth (Hz)", clip.param("bandwidthHz", 4000), 100, 100000, 100,
                    v -> { suppressRefresh = true; try { editSession.setClipParam(clip.id(), "bandwidthHz", v); } finally { suppressRefresh = false; } });
                row = addInspectorField(grid, row, "Center Offset (μs)", clip.param("centerOffset", 0), -10000, 10000, 1,
                    v -> { suppressRefresh = true; try { editSession.setClipParam(clip.id(), "centerOffset", v); } finally { suppressRefresh = false; } });
                row = addInspectorField(grid, row, "Window Factor", clip.param("windowFactor", 1), 0.1, 5, 0.1,
                    v -> { suppressRefresh = true; try { editSession.setClipParam(clip.id(), "windowFactor", v); } finally { suppressRefresh = false; } });
            }
            case TRAPEZOID -> {
                row = addInspectorField(grid, row, "Rise Time (μs)", clip.param("riseTime", clip.duration() * 0.15), 0, clip.duration(), 1,
                    v -> { suppressRefresh = true; try { editSession.setClipParam(clip.id(), "riseTime", v); } finally { suppressRefresh = false; } });
                row = addInspectorField(grid, row, "Flat Time (μs)", clip.param("flatTime", clip.duration() * 0.5), 0, clip.duration(), 1,
                    v -> { suppressRefresh = true; try { editSession.setClipParam(clip.id(), "flatTime", v); } finally { suppressRefresh = false; } });
            }
            case GAUSSIAN -> {
                row = addInspectorField(grid, row, "Sigma (μs)", clip.param("sigma", clip.duration() * 0.2), 1, clip.duration(), 1,
                    v -> { suppressRefresh = true; try { editSession.setClipParam(clip.id(), "sigma", v); } finally { suppressRefresh = false; } });
            }
            case TRIANGLE -> {
                row = addInspectorField(grid, row, "Peak Position", clip.param("peakPosition", 0.5), 0, 1, 0.05,
                    v -> { suppressRefresh = true; try { editSession.setClipParam(clip.id(), "peakPosition", v); } finally { suppressRefresh = false; } });
            }
            case SPLINE -> {
                content.getChildren().add(new Label(clip.splinePoints().size() + " control points"));
            }
            default -> {}
        }

        content.getChildren().add(grid);

        // Action buttons
        content.getChildren().add(new Separator());
        content.getChildren().add(actionRow(
            button("Delete", editSession::deleteSelectedClips),
            button("Duplicate", editSession::duplicateSelectedClips)
        ));
    }

    private int addInspectorField(GridPane grid, int row, String label, double value,
                                   double min, double max, double step,
                                   java.util.function.DoubleConsumer onUpdate) {
        var lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 10px;");
        lbl.setMinWidth(90);

        var factory = new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, value, step);
        var spinner = new Spinner<Double>(factory);
        spinner.setEditable(true);
        spinner.setPrefWidth(110);
        spinner.setStyle("-fx-font-size: 10px;");

        spinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !suppressRefresh) {
                onUpdate.accept(newVal);
            }
        });

        grid.add(lbl, 0, row);
        grid.add(spinner, 1, row);
        return row + 1;
    }

    /**
     * Add a field that displays and accepts SI-prefixed values (e.g. "15 μT", "20 mT/m").
     * The internal value is always in base SI units (T, T/m, dimensionless).
     * The channel's unit already includes an SI prefix (μT, mT/m), so the converter
     * uses a fixed display scale and unit string.
     */
    private int addSiField(GridPane grid, int row, String label, double value,
                            SignalChannel channel, java.util.function.DoubleConsumer onUpdate) {
        var lbl = new Label(label);
        lbl.setStyle("-fx-font-size: 10px;");
        lbl.setMinWidth(90);

        var converter = SiPrefixConverter.forChannel(channel);

        var textField = new TextField(converter.format(value));
        textField.setPrefWidth(110);
        textField.setStyle("-fx-font-size: 10px;");

        // Commit on Enter
        textField.setOnAction(e -> {
            double parsed = converter.parse(textField.getText());
            if (!Double.isNaN(parsed) && !suppressRefresh) {
                onUpdate.accept(parsed);
                // Reformat to canonical display (e.g. "15u" → "15 μT")
                textField.setText(converter.format(parsed));
            }
        });
        // Commit and reformat on focus loss
        textField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                double parsed = converter.parse(textField.getText());
                if (!Double.isNaN(parsed) && !suppressRefresh) {
                    onUpdate.accept(parsed);
                }
                // Always reformat on blur so "15u" becomes "15 μT"
                textField.setText(converter.format(parsed));
            }
        });

        grid.add(lbl, 0, row);
        grid.add(textField, 1, row);
        return row + 1;
    }

    /**
     * Formats and parses amplitude values with SI-prefixed units.
     *
     * <p>Each channel has a <em>display unit</em> (e.g. "μT" for B₁) and a
     * <em>display scale</em> (e.g. 1e-6 for μ). The raw value in base SI (Tesla)
     * is divided by the display scale for display, and multiplied back on parse.
     * Users can type bare numbers (interpreted in display units) or use SI prefix
     * letters: {@code 15u} = 15×10⁻⁶, {@code 20m} = 20×10⁻³, etc. On blur the
     * field reformats to the canonical display (e.g. "15u" → "15 μT").
     */
    private static final class SiPrefixConverter {
        private final String displayUnit;  // e.g. "μT", "mT/m", ""
        private final double displayScale; // e.g. 1e-6 for μT

        private SiPrefixConverter(String displayUnit, double displayScale) {
            this.displayUnit = displayUnit;
            this.displayScale = displayScale;
        }

        static SiPrefixConverter forChannel(SignalChannel ch) {
            return switch (ch) {
                case B1X, B1Y -> new SiPrefixConverter("μT", 1e-6);
                case GX, GZ   -> new SiPrefixConverter("mT/m", 1e-3);
                case RF_GATE  -> new SiPrefixConverter("", 1);
            };
        }

        /** Format a raw SI value for display. */
        String format(double rawValue) {
            if (Double.isNaN(rawValue)) return "";
            double display = displayScale != 0 ? rawValue / displayScale : rawValue;
            String num = formatNumber(display);
            return displayUnit.isEmpty() ? num : num + " " + displayUnit;
        }

        /** Parse a user-entered string back to raw SI value. */
        double parse(String text) {
            if (text == null || text.isBlank()) return Double.NaN;
            text = text.strip();

            // Strip the display unit suffix if present
            if (!displayUnit.isEmpty() && text.endsWith(displayUnit)) {
                text = text.substring(0, text.length() - displayUnit.length()).strip();
            }

            // Check for an SI prefix letter at the end
            if (!text.isEmpty()) {
                char last = text.charAt(text.length() - 1);
                Double prefixScale = prefixScale(last);
                if (prefixScale != null) {
                    String numPart = text.substring(0, text.length() - 1).strip();
                    try {
                        return Double.parseDouble(numPart) * prefixScale;
                    } catch (NumberFormatException e) {
                        return Double.NaN;
                    }
                }
            }

            // Bare number → interpreted in display units
            try {
                return Double.parseDouble(text) * displayScale;
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }

        private static String formatNumber(double v) {
            if (v == 0) return "0";
            double abs = Math.abs(v);
            String num;
            if (abs >= 100) num = String.format("%.1f", v);
            else if (abs >= 10) num = String.format("%.2f", v);
            else if (abs >= 1) num = String.format("%.3f", v);
            else num = String.format("%.4f", v);
            if (num.contains(".")) {
                num = num.replaceAll("0+$", "").replaceAll("\\.$", "");
            }
            return num;
        }

        private static Double prefixScale(char c) {
            return switch (c) {
                case 'f' -> 1e-15;
                case 'p' -> 1e-12;
                case 'n' -> 1e-9;
                case 'u', 'μ' -> 1e-6;
                case 'm' -> 1e-3;
                case 'k' -> 1e3;
                case 'M' -> 1e6;
                case 'G' -> 1e9;
                default -> null;
            };
        }
    }

    private void populateBookmark(RunBookmarkDocument bookmark) {
        content.getChildren().add(infoLine("Bookmark", bookmark.name()));
        content.getChildren().add(infoLine("Iteration", bookmark.iterationKey()));
        content.getChildren().add(actionRow(
            button("Open Run", () -> paneContext.session().project.openNode(bookmark.id()))
        ));
    }

    private Node header(ProjectNode node) {
        var icon = switch (node.kind()) {
            case ProjectNodeKind.IMPORT_LINK -> StudioIcons.create(StudioIconKind.IMPORT);
            case ProjectNodeKind.IMPORTED_SCENARIO -> StudioIcons.create(
                node instanceof ImportedScenarioDocument scenario && scenario.iterative()
                    ? StudioIconKind.RUN
                    : StudioIconKind.SCENARIO
            );
            case ProjectNodeKind.IMPORTED_OPTIMISATION_RUN, ProjectNodeKind.OPTIMISATION_RUN -> StudioIcons.create(StudioIconKind.RUN);
            case ProjectNodeKind.IMPORTED_CAPTURE, ProjectNodeKind.CAPTURE -> StudioIcons.create(StudioIconKind.CAPTURE);
            case ProjectNodeKind.SEQUENCE_SNAPSHOT -> StudioIcons.create(StudioIconKind.SNAPSHOT);
            case ProjectNodeKind.SEQUENCE -> StudioIcons.create(StudioIconKind.SEQUENCE);
            case ProjectNodeKind.SIMULATION -> StudioIcons.create(StudioIconKind.SIMULATION);
            case ProjectNodeKind.OPTIMISATION_CONFIG -> StudioIcons.create(StudioIconKind.OPTIMISATION_CONFIG);
            case ProjectNodeKind.RUN_BOOKMARK -> StudioIcons.create(StudioIconKind.BOOKMARK);
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
        void detach() {
            observable.removeListener(listener);
        }
    }
}
