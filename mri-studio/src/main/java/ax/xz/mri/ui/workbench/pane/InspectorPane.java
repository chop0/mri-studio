package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.project.EigenfieldDocument;
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
                    paneContext.session().points.entries);
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

        var snap = new NumberField().range(0, 1_000_000).step(10).decimals(0).unit("μs");
        snap.setValue(editSession.snapGridSize.get());
        snap.valueProperty().addListener((obs, o, n) -> {
            if (n != null) editSession.snapGridSize.set(n.doubleValue());
        });
        editSession.snapGridSize.addListener((obs, o, n) -> snap.setValueQuiet(n.doubleValue()));
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
            case SIMULATION_CONFIG, CIRCUIT -> StudioIcons.create(StudioIconKind.SIMULATION);
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
