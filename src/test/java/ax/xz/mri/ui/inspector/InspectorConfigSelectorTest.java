package ax.xz.mri.ui.inspector;

import ax.xz.mri.model.simulation.PhysicsParams;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SimulationConfigDocument;
import ax.xz.mri.support.FxTestSupport;
import ax.xz.mri.ui.edit.EditSession;
import ax.xz.mri.ui.viewmodel.StudioSession;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the sim/hardware config selector at the top of the inspector. With
 * two sim configs in the project, the combo lists both; choosing one wires
 * {@code editSession.activeSimConfigId}.
 */
class InspectorConfigSelectorTest {

    @Test
    void simComboListsAllAndSetsActiveOnPick() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new StudioSession();
            var simA = new SimulationConfigDocument(new ProjectNodeId("a"), "Config A",
                new SimulationConfig(0.05, PhysicsParams.DEFAULTS.dtSeconds(), null));
            var simB = new SimulationConfigDocument(new ProjectNodeId("b"), "Config B",
                new SimulationConfig(0.05, PhysicsParams.DEFAULTS.dtSeconds(), null));
            // Inject directly into the state.
            var current = session.state.current();
            session.state.replaceState(current
                .withSimulation(simA)
                .withSimulation(simB));

            var editSession = new EditSession();
            var selector = SequenceConfigSelector.build(session, editSession);

            var combos = collectCombos((Parent) selector);
            assertTrue(combos.size() >= 2,
                "Expected sim + hardware combos, got " + combos.size());

            // Sim combo lists null + 2 configs (3 items total).
            @SuppressWarnings("unchecked")
            var simCombo = (ComboBox<ProjectNodeId>) combos.get(0);
            assertEquals(3, simCombo.getItems().size(),
                "Sim combo must include the null placeholder + every sim config");

            // Pick one — editSession reflects it.
            simCombo.setValue(simA.id());
            assertEquals(simA.id(), editSession.activeSimConfigId.get());
            simCombo.setValue(simB.id());
            assertEquals(simB.id(), editSession.activeSimConfigId.get());
        });
    }

    private static List<ComboBox<?>> collectCombos(Parent root) {
        var sink = new ArrayList<ComboBox<?>>();
        for (var child : root.getChildrenUnmodifiable()) {
            if (child instanceof ComboBox<?> cb) sink.add(cb);
            if (child instanceof Parent p) sink.addAll(collectCombos(p));
        }
        return sink;
    }
}
