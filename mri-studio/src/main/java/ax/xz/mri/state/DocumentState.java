package ax.xz.mri.state;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;

/**
 * Holds the loaded document, the active scenario / iteration selection,
 * and derives the current pulse waveform.
 */
public class DocumentState {
    public final ObjectProperty<BlochData>    blochData        = new SimpleObjectProperty<>();
    public final StringProperty               currentScenario  = new SimpleStringProperty();
    public final IntegerProperty              iterationIndex   = new SimpleIntegerProperty(0);
    public final ObservableList<String>       iterationKeys    = FXCollections.observableArrayList();

    /** The active pulse (derived from blochData + currentScenario + iterationIndex). */
    public final ObjectProperty<List<PulseSegment>> currentPulse = new SimpleObjectProperty<>();

    public DocumentState() {
        blochData.addListener((obs, old, data) -> {
            if (data == null) { currentScenario.set(null); iterationKeys.clear(); return; }
            // pick first scenario
            var keys = data.scenarios().keySet().stream().sorted().toList();
            currentScenario.set(keys.isEmpty() ? null : keys.get(0));
        });

        currentScenario.addListener((obs, old, scen) -> refreshIterationKeys());
        iterationIndex.addListener((obs, old, idx)   -> refreshCurrentPulse());
    }

    private void refreshIterationKeys() {
        var data = blochData.get();
        var scen = currentScenario.get();
        iterationKeys.clear();
        if (data == null || scen == null || !data.scenarios().containsKey(scen)) return;
        iterationKeys.setAll(data.scenarios().get(scen).iterationKeys());
        iterationIndex.set(Math.max(0, iterationKeys.size() - 1));
        refreshCurrentPulse();
    }

    private void refreshCurrentPulse() {
        var data = blochData.get();
        var scen = currentScenario.get();
        if (data == null || scen == null || iterationKeys.isEmpty()) { currentPulse.set(null); return; }
        int idx = iterationIndex.get();
        if (idx < 0 || idx >= iterationKeys.size()) { currentPulse.set(null); return; }
        var key  = iterationKeys.get(idx);
        var segs = data.scenarios().get(scen).pulses().get(key);
        currentPulse.set(segs);
    }
}
