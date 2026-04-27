package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.hardware.HardwarePlugin;
import ax.xz.mri.hardware.HardwarePluginRegistry;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.HardwareConfigDocument;
import ax.xz.mri.project.ProjectNode;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.project.SimulationConfigDocument;
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
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Context-sensitive right sidebar.
 *
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

    private final List<ListenerBinding<?>> activeBindings = new ArrayList<>();
    private ClipInspectorSection activeClipSection;
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

    private void rebuild() {
        for (var binding : activeBindings) binding.detach();
        activeBindings.clear();
        if (activeClipSection != null) {
            activeClipSection.dispose();
            activeClipSection = null;
        }

        content.getChildren().clear();

        var editSession = paneContext.session().activeEditSession.get();
        if (editSession != null) {
            wireEditSession(editSession);
            content.getChildren().addAll(sequenceSection(editSession), new Separator());

            var primaryClip = editSession.primarySelectedClip();
            if (primaryClip != null) {
                activeClipSection = new ClipInspectorSection(
                    editSession, primaryClip,
                    paneContext.session().points.entries,
                    this::handleSchematicHighlightRequest);
                content.getChildren().add(activeClipSection.view());
            } else {
                var empty = new Label("No clip selected.");
                empty.getStyleClass().add("cfg-row-hint");
                content.getChildren().add(empty);
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
            case SequenceDocument sequence -> populateSequence(sequence);
            case SimulationConfigDocument simConfig -> populateSimConfig(simConfig);
            case HardwareConfigDocument hwConfig -> populateHardwareConfig(hwConfig);
            case EigenfieldDocument eigenfield -> {
                content.getChildren().add(infoLine("Type", "Eigenfield"));
                content.getChildren().add(infoLine("Name", eigenfield.name()));
                content.getChildren().add(infoLine("Description",
                    eigenfield.description() == null ? "" : eigenfield.description()));
            }
            case CircuitDocument circuit -> populateCircuit(circuit);
            default -> content.getChildren().add(infoLine("Kind", node.kind().name()));
        }
    }

    private void wireEditSession(SequenceEditSession editSession) {
        this.wiredSession = editSession;

        InvalidationListener primaryListener = obs -> rebuild();
        editSession.primarySelectedClipId.addListener(primaryListener);
        activeBindings.add(new ListenerBinding<>(editSession.primarySelectedClipId, primaryListener));

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

    private Node sequenceSection(SequenceEditSession editSession) {
        var box = new VBox(8);

        var title = new Label("Sequence");
        title.getStyleClass().add("clip-inspector-section");
        box.getChildren().add(title);

        var duration = new NumberField().range(10, 1_000_000_000).step(100).decimals(0).unit("μs");
        duration.setValue(editSession.totalDuration.get());
        duration.valueProperty().addListener((obs, o, n) -> {
            if (n != null) editSession.setTotalDuration(n.doubleValue());
        });
        editSession.totalDuration.addListener((obs, o, n) -> duration.setValueQuiet(n.doubleValue()));
        box.getChildren().add(row("Duration", duration));

        var snap = new NumberField().range(0, 1_000_000).step(10).decimals(0).unit("μs")
            .bindBidirectional(editSession.snapGridSize);
        box.getChildren().add(row("Snap grid", snap));

        var snapToggle = new CheckBox("Snap to clip edges + grid");
        snapToggle.setSelected(editSession.snapEnabled.get());
        snapToggle.selectedProperty().bindBidirectional(editSession.snapEnabled);
        box.getChildren().add(snapToggle);

        box.getChildren().add(new Separator());

        var simTitle = new Label("Simulation");
        simTitle.getStyleClass().add("clip-inspector-section");
        box.getChildren().add(simTitle);

        var repo = paneContext.session().project.repository.get();
        var configs = repo.simConfigIds().stream()
            .map(id -> (SimulationConfigDocument) repo.node(id))
            .filter(Objects::nonNull)
            .toList();

        if (configs.isEmpty()) {
            var empty = new Label("No simulation configs. Create one via File menu.");
            empty.getStyleClass().add("cfg-row-hint");
            box.getChildren().add(empty);
        } else {
            var combo = new ComboBox<String>();
            combo.setPrefWidth(200);
            for (var cfg : configs) combo.getItems().add(cfg.name());

            Consumer<ProjectNodeId> syncComboFromSession = id -> {
                String target = null;
                if (id != null) {
                    for (var cfg : configs) if (cfg.id().equals(id)) { target = cfg.name(); break; }
                }
                if (!Objects.equals(combo.getValue(), target)) {
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
            var runBtn = new Button("Run");
            runBtn.setGraphic(StudioIcons.create(StudioIconKind.RUN));
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

        // ── Hardware section ────────────────────────────────────────────────
        // Lives in the inspector (rather than the toolbar) so the user can
        // pick a config and trigger a run from the same place they edit the
        // sequence's other parameters.
        box.getChildren().add(new Separator());
        box.getChildren().add(buildHardwareSection(editSession));

        return box;
    }

    /**
     * Hardware controls: pick a project hardware config from a combo, then
     * (when one is bound) hit "Run on hardware" to push the baked sequence
     * at the device. The combo lists every {@link HardwareConfigDocument} in
     * the project plus a "(none)" entry so the user can detach.
     */
    private Node buildHardwareSection(SequenceEditSession editSession) {
        var box = new VBox(8);
        var title = new Label("Hardware");
        title.getStyleClass().add("clip-inspector-section");
        box.getChildren().add(title);

        var repo = paneContext.session().project.repository.get();
        var hwConfigs = repo.hardwareConfigIds().stream()
            .map(id -> (HardwareConfigDocument) repo.node(id))
            .filter(Objects::nonNull)
            .toList();

        if (hwConfigs.isEmpty()) {
            var empty = new Label("No hardware configs. Create one via File menu.");
            empty.getStyleClass().add("cfg-row-hint");
            box.getChildren().add(empty);
            return box;
        }

        final String NONE_LABEL = "(none)";
        var combo = new ComboBox<String>();
        combo.setPrefWidth(200);
        combo.getItems().add(NONE_LABEL);
        for (var hw : hwConfigs) combo.getItems().add(hw.name());

        var hwSession = findHardwareSessionForEditor(editSession);

        Runnable syncComboFromSession = () -> {
            var active = editSession.activeHardwareConfig.get();
            String target = active == null ? NONE_LABEL : active.name();
            if (!Objects.equals(combo.getValue(), target)) {
                var old = combo.getOnAction();
                combo.setOnAction(null);
                combo.setValue(target);
                combo.setOnAction(old);
            }
        };
        syncComboFromSession.run();
        InvalidationListener hwListener = obs -> syncComboFromSession.run();
        editSession.activeHardwareConfig.addListener(hwListener);
        activeBindings.add(new ListenerBinding<>(editSession.activeHardwareConfig, hwListener));

        combo.setOnAction(e -> {
            var selected = combo.getValue();
            HardwareConfigDocument target = null;
            if (selected != null && !selected.equals(NONE_LABEL)) {
                for (var hw : hwConfigs) {
                    if (hw.name().equals(selected)) { target = hw; break; }
                }
            }
            // Push through the run session so its active-config property and
            // editSession.activeHardwareConfig stay in lockstep (the session
            // mirrors back via its own listener). If no run session is
            // attached, set the editSession view directly so the timeline
            // routing menus still update.
            if (hwSession != null) hwSession.activeConfig.set(target);
            else editSession.activeHardwareConfig.set(target);
        });
        box.getChildren().add(row("Config", combo));

        // Rebuild the run row in place each time the active config changes —
        // when no config is bound we show a hint instead of a disabled button
        // (a hint reads better than an inert button).
        var runRow = new HBox(8);
        runRow.setAlignment(Pos.CENTER_LEFT);
        Runnable rebuildRunRow = () -> {
            runRow.getChildren().clear();
            var active = editSession.activeHardwareConfig.get();
            if (active == null) {
                var hint = new Label("Pick a config above to enable Run on hardware.");
                hint.getStyleClass().add("cfg-row-hint");
                runRow.getChildren().add(hint);
                return;
            }
            var runHwBtn = new Button("Run on hardware");
            runHwBtn.setGraphic(StudioIcons.create(StudioIconKind.RUN));
            runHwBtn.setOnAction(e -> { if (hwSession != null) hwSession.run(); });
            // Disable while a run is already in flight so we don't queue
            // duplicate I/O at the device.
            if (hwSession != null) runHwBtn.disableProperty().bind(hwSession.running);
            runRow.getChildren().add(runHwBtn);
        };
        rebuildRunRow.run();
        InvalidationListener runRowListener = obs -> rebuildRunRow.run();
        editSession.activeHardwareConfig.addListener(runRowListener);
        activeBindings.add(new ListenerBinding<>(editSession.activeHardwareConfig, runRowListener));
        box.getChildren().add(runRow);

        // Progress bar — only visible while a run is in flight. Driven by the
        // run session's progress property; spans the section's full width.
        if (hwSession != null) {
            var progress = new ProgressBar();
            progress.setMaxWidth(Double.MAX_VALUE);
            progress.progressProperty().bind(hwSession.progress);
            // Hide AND collapse the row when not running so the inspector
            // doesn't reserve dead vertical space.
            progress.visibleProperty().bind(hwSession.running);
            progress.managedProperty().bind(hwSession.running);
            box.getChildren().add(progress);
        }

        return box;
    }

    private ax.xz.mri.ui.viewmodel.HardwareRunSession findHardwareSessionForEditor(
            SequenceEditSession editSession) {
        return paneContext.controller().allHardwareSessions().stream()
            .filter(s -> s.editSession == editSession)
            .findFirst()
            .orElse(null);
    }

    private ax.xz.mri.ui.viewmodel.SequenceSimulationSession findSimSessionForEditor(SequenceEditSession editSession) {
        return paneContext.controller().allSimSessions().stream()
            .filter(s -> s.editSession == editSession)
            .findFirst()
            .orElse(null);
    }

    /**
     * Handle a "show this path in the schematic" request from the clip
     * inspector. Resolves the request's {@code circuitId} to its owning sim
     * config, opens (or focuses) that tab, switches to the Schematic sub-tab,
     * and pushes the highlight overlay onto the schematic canvas.
     *
     * <p>If the circuit has no owning sim config (orphaned), falls back to
     * just searching open tabs — and if nothing matches, no-ops with a
     * status nudge.
     */
    private void handleSchematicHighlightRequest(ax.xz.mri.ui.preview.SchematicHighlightRequest request) {
        if (request == null) return;
        var controller = paneContext.controller();
        var repo = paneContext.session().project.repository.get();
        if (repo == null) return;

        // Find a sim config whose circuitId matches.
        SimulationConfigDocument owningConfig = null;
        for (var id : repo.simConfigIds()) {
            if (repo.node(id) instanceof SimulationConfigDocument cfg
                    && cfg.config() != null
                    && request.circuitId().equals(cfg.config().circuitId())) {
                owningConfig = cfg;
                break;
            }
        }
        if (owningConfig == null) return;

        controller.openSimConfigTab(owningConfig);
        var provider = controller.findSimConfigEditor(owningConfig.id()).orElse(null);
        if (provider == null) return;
        var editor = provider.editorPane();
        editor.selectSchematicTab();
        var schematic = editor.schematicPane();
        if (schematic != null) {
            schematic.showPathHighlight(request.components(), request.wireIds());
        }
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
            content.getChildren().add(infoLine("B\u2080 ref", String.format("%.4f T", cfg.referenceB0Tesla())));
            content.getChildren().add(infoLine("T\u2081", String.format("%.0f ms", cfg.t1Ms())));
            content.getChildren().add(infoLine("T\u2082", String.format("%.0f ms", cfg.t2Ms())));
            var repo = paneContext.session().project.repository.get();
            var circuit = repo.circuit(cfg.circuitId());
            if (circuit != null) {
                content.getChildren().add(infoLine("Circuit", circuit.name()));
                content.getChildren().add(infoLine("Drive sources", String.valueOf(circuit.voltageSources().size())));
                content.getChildren().add(infoLine("Coils", String.valueOf(circuit.coils().size())));
                content.getChildren().add(infoLine("Probes", String.valueOf(circuit.probes().size())));
            }
        }
        content.getChildren().add(new Separator());
        content.getChildren().add(actionRow(
            button("Open Editor", () -> paneContext.session().project.openNode(simConfig.id())),
            button("Delete", () -> paneContext.session().project.deleteSimConfig(simConfig.id()))
        ));
    }

    /**
     * Inspector view for a {@link HardwareConfigDocument}. Embeds the plugin's
     * own {@link ax.xz.mri.hardware.HardwareConfigEditor} so editing happens
     * right in the navigator panel — no extra tab to manage. Live edits push
     * back to the project via {@code updateHardwareConfig}.
     */
    private void populateHardwareConfig(HardwareConfigDocument hwConfig) {
        var pluginId = hwConfig.config() != null ? hwConfig.config().pluginId() :
                       (hwConfig.envelope() != null ? hwConfig.envelope().pluginId() : null);
        var plugin = pluginId == null ? null : HardwarePluginRegistry.byId(pluginId).orElse(null);

        content.getChildren().add(infoLine("Type", "Hardware Config"));
        content.getChildren().add(infoLine("Name", hwConfig.name()));
        content.getChildren().add(infoLine("Plugin", plugin != null ? plugin.displayName() : (pluginId == null ? "—" : pluginId)));

        if (plugin != null) {
            var caps = plugin.capabilities();
            content.getChildren().add(infoLine("Outputs", String.valueOf(caps.outputChannels().size())));
            content.getChildren().add(infoLine("Probes", String.valueOf(caps.probeNames().size())));
            content.getChildren().add(infoLine("Max sample rate",
                String.format("%.1f MS/s", caps.maxSampleRateHz() * 1e-6)));
        } else {
            var missing = new Label("Plugin not loaded — open a project that ships with this plugin to edit.");
            missing.getStyleClass().add("cfg-row-warn");
            missing.setWrapText(true);
            content.getChildren().add(missing);
        }

        content.getChildren().add(new Separator());
        content.getChildren().add(actionRow(
            button("Open Editor", () -> paneContext.session().project.openNode(hwConfig.id())),
            button("Rename", () -> renameHardwareConfig(hwConfig.id())),
            button("Delete", () -> paneContext.session().project.deleteHardwareConfig(hwConfig.id()))
        ));
    }

    private void renameHardwareConfig(ProjectNodeId configId) {
        var repository = paneContext.session().project.repository.get();
        if (!(repository.node(configId) instanceof HardwareConfigDocument hw)) return;
        var dialog = new TextInputDialog(hw.name());
        dialog.setTitle("Rename Hardware Config");
        dialog.setHeaderText("Rename hardware config");
        dialog.setContentText("Name:");
        dialog.showAndWait().map(String::trim).filter(value -> !value.isBlank())
            .ifPresent(value -> paneContext.session().project.renameHardwareConfig(configId, value));
    }

    private void populateCircuit(CircuitDocument circuit) {
        content.getChildren().add(infoLine("Type", "Circuit"));
        content.getChildren().add(infoLine("Name", circuit.name()));
        content.getChildren().add(infoLine("Components", String.valueOf(circuit.components().size())));
        content.getChildren().add(infoLine("Wires", String.valueOf(circuit.wires().size())));
        content.getChildren().add(infoLine("Drive sources", String.valueOf(circuit.voltageSources().size())));
        content.getChildren().add(infoLine("Coils", String.valueOf(circuit.coils().size())));
        content.getChildren().add(infoLine("Probes", String.valueOf(circuit.probes().size())));
        content.getChildren().add(new Separator());
        var repo = paneContext.session().project.repository.get();
        var owningConfig = repo.simConfigIds().stream()
            .map(id -> (SimulationConfigDocument) repo.node(id))
            .filter(Objects::nonNull)
            .filter(cfg -> cfg.config() != null && circuit.id().equals(cfg.config().circuitId()))
            .findFirst().orElse(null);
        if (owningConfig != null) {
            var openBtn = button("Open in editor",
                () -> paneContext.session().project.openNode(owningConfig.id()));
            content.getChildren().add(actionRow(openBtn));
        }
    }

    private Node header(ProjectNode node) {
        var icon = switch (node.kind()) {
            case SEQUENCE -> StudioIcons.create(StudioIconKind.SEQUENCE);
            case SIMULATION_CONFIG, CIRCUIT, HARDWARE_CONFIG -> StudioIcons.create(StudioIconKind.SIMULATION);
            case EIGENFIELD -> StudioIcons.create(StudioIconKind.EIGENFIELD);
        };
        var title = new Label(ProjectDisplayNames.label(node));
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

    private record ListenerBinding<T extends javafx.beans.Observable>(T observable, InvalidationListener listener) {
        void detach() { observable.removeListener(listener); }
    }
}
