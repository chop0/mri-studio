package ax.xz.mri.ui.inspector;

import ax.xz.mri.project.HardwareConfigDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.ui.edit.EditSession;
import ax.xz.mri.ui.viewmodel.StudioSession;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.util.StringConverter;

import java.util.ArrayList;

/**
 * Two combos at the top of the inspector for binding the active sequence to
 * a sim config and (optionally) a hardware config. Always shown when a
 * sequence document is open, regardless of clip selection — the user
 * configures the simulator directly here without opening the sim-config
 * tab.
 *
 * <p>Built once per (session × edit-session) pair; survives clip-selection
 * churn untouched.
 */
final class SequenceConfigSelector {
    private SequenceConfigSelector() {}

    static Node build(StudioSession session, EditSession editSession) {
        var simCombo = new ComboBox<ProjectNodeId>();
        simCombo.setPromptText("(no sim config)");
        simCombo.setConverter(simConverter(session));
        simCombo.setMaxWidth(Double.MAX_VALUE);

        var hwCombo = new ComboBox<ProjectNodeId>();
        hwCombo.setPromptText("(no hardware)");
        hwCombo.setConverter(hwConverter(session));
        hwCombo.setMaxWidth(Double.MAX_VALUE);

        Runnable refreshItems = () -> {
            var repo = session.state.current();
            var sims = new ArrayList<ProjectNodeId>();
            sims.add(null);
            sims.addAll(repo.simulationIds());
            simCombo.getItems().setAll(sims);

            var hws = new ArrayList<ProjectNodeId>();
            hws.add(null);
            hws.addAll(repo.hardwareIds());
            hwCombo.getItems().setAll(hws);

            simCombo.setValue(editSession.activeSimConfigId.get());
            hwCombo.setValue(editSession.activeHardwareConfigId.get());
        };
        refreshItems.run();
        session.project.explorer.structureRevision.addListener((obs, o, n) -> refreshItems.run());

        // Mirror model → combo on external setActiveSimConfig (e.g. undo).
        editSession.activeSimConfigId.addListener((obs, o, n) -> simCombo.setValue(n));
        editSession.activeHardwareConfigId.addListener((obs, o, n) -> hwCombo.setValue(n));

        // Push combo → model on user pick.
        simCombo.valueProperty().addListener((obs, o, n) -> {
            if (java.util.Objects.equals(n, editSession.activeSimConfigId.get())) return;
            editSession.setActiveSimConfig(n);
        });
        hwCombo.valueProperty().addListener((obs, o, n) -> {
            if (java.util.Objects.equals(n, editSession.activeHardwareConfigId.get())) return;
            editSession.activeHardwareConfigId.set(n);
        });

        var grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(4);
        grid.add(new Label("Sim config"), 0, 0);
        grid.add(simCombo, 1, 0);
        grid.add(new Label("Hardware"), 0, 1);
        grid.add(hwCombo, 1, 1);
        javafx.scene.layout.GridPane.setHgrow(simCombo, javafx.scene.layout.Priority.ALWAYS);
        javafx.scene.layout.GridPane.setHgrow(hwCombo, javafx.scene.layout.Priority.ALWAYS);
        return grid;
    }

    private static StringConverter<ProjectNodeId> simConverter(StudioSession session) {
        return new StringConverter<>() {
            @Override public String toString(ProjectNodeId id) {
                if (id == null) return "(no sim config)";
                var doc = session.state.current().simulation(id);
                return doc instanceof SimulationConfigDocument s ? s.name() : id.value();
            }
            @Override public ProjectNodeId fromString(String s) { return null; }
        };
    }

    private static StringConverter<ProjectNodeId> hwConverter(StudioSession session) {
        return new StringConverter<>() {
            @Override public String toString(ProjectNodeId id) {
                if (id == null) return "(no hardware)";
                var doc = session.state.current().hardwareConfig(id);
                return doc instanceof HardwareConfigDocument h ? h.name() : id.value();
            }
            @Override public ProjectNodeId fromString(String s) { return null; }
        };
    }
}
