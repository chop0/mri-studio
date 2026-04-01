package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.File;
import java.util.List;

/** Current document/session selection state: file, scenario, iteration, and active pulse. */
public class DocumentSessionViewModel {
    public final ObjectProperty<File> currentFile = new SimpleObjectProperty<>();
    public final ObjectProperty<BlochData> blochData = new SimpleObjectProperty<>();
    public final StringProperty currentScenario = new SimpleStringProperty();
    public final IntegerProperty iterationIndex = new SimpleIntegerProperty(0);
    public final ObservableList<String> scenarioKeys = FXCollections.observableArrayList();
    public final ObservableList<String> iterationKeys = FXCollections.observableArrayList();
    public final ObjectProperty<List<PulseSegment>> currentPulse = new SimpleObjectProperty<>();
    private boolean suppressRefresh;

    public DocumentSessionViewModel() {
        blochData.addListener((obs, oldData, newData) -> refreshScenarios());
        currentScenario.addListener((obs, oldScenario, newScenario) -> refreshIterations());
        iterationIndex.addListener((obs, oldValue, newValue) -> refreshCurrentPulse());
    }

    public void setDocument(File file, BlochData data) {
        currentFile.set(file);
        blochData.set(data);
    }

    public void showCapture(File file, BlochData data, String scenarioKey, String iterationKey) {
        suppressRefresh = true;
        try {
            currentFile.set(file);
            blochData.set(data);

            if (data == null || data.scenarios() == null || data.scenarios().isEmpty()) {
                scenarioKeys.clear();
                iterationKeys.clear();
                currentPulse.set(null);
                currentScenario.set(null);
                iterationIndex.set(0);
                return;
            }

            var resolvedScenarioKeys = data.scenarios().keySet().stream().sorted().toList();
            String resolvedScenario = scenarioKey != null && data.scenarios().containsKey(scenarioKey)
                ? scenarioKey
                : resolvedScenarioKeys.getFirst();
            var scenario = data.scenarios().get(resolvedScenario);
            var resolvedIterationKeys = scenario.iterationKeys();
            int resolvedIndex = iterationKey == null ? resolvedIterationKeys.size() - 1 : resolvedIterationKeys.indexOf(iterationKey);
            if (resolvedIndex < 0) resolvedIndex = resolvedIterationKeys.isEmpty() ? 0 : resolvedIterationKeys.size() - 1;
            List<PulseSegment> resolvedPulse = resolvedIterationKeys.isEmpty()
                ? null
                : scenario.pulses().get(resolvedIterationKeys.get(resolvedIndex));

            scenarioKeys.setAll(resolvedScenarioKeys);
            currentScenario.set(resolvedScenario);
            iterationKeys.setAll(resolvedIterationKeys);
            if (resolvedIterationKeys.isEmpty()) {
                iterationIndex.set(0);
                currentPulse.set(null);
                return;
            }
            iterationIndex.set(resolvedIndex);
            currentPulse.set(resolvedPulse);
        } finally {
            suppressRefresh = false;
        }
    }

    public void clearDocument() {
        suppressRefresh = true;
        try {
            currentFile.set(null);
            blochData.set(null);
            scenarioKeys.clear();
            iterationKeys.clear();
            currentScenario.set(null);
            currentPulse.set(null);
            iterationIndex.set(0);
        } finally {
            suppressRefresh = false;
        }
    }

    private void refreshScenarios() {
        if (suppressRefresh) return;
        scenarioKeys.clear();
        iterationKeys.clear();
        currentPulse.set(null);

        var data = blochData.get();
        if (data == null || data.scenarios() == null) {
            currentScenario.set(null);
            return;
        }

        scenarioKeys.setAll(data.scenarios().keySet().stream().sorted().toList());
        currentScenario.set(scenarioKeys.isEmpty() ? null : scenarioKeys.get(0));
    }

    private void refreshIterations() {
        if (suppressRefresh) return;
        iterationKeys.clear();
        currentPulse.set(null);

        var data = blochData.get();
        var scenario = currentScenario.get();
        if (data == null || scenario == null || !data.scenarios().containsKey(scenario)) {
            return;
        }

        iterationKeys.setAll(data.scenarios().get(scenario).iterationKeys());
        iterationIndex.set(Math.max(0, iterationKeys.size() - 1));
        refreshCurrentPulse();
    }

    private void refreshCurrentPulse() {
        if (suppressRefresh) return;
        var data = blochData.get();
        var scenario = currentScenario.get();
        if (data == null || scenario == null || iterationKeys.isEmpty()) {
            currentPulse.set(null);
            return;
        }

        int index = Math.max(0, Math.min(iterationIndex.get(), iterationKeys.size() - 1));
        iterationIndex.set(index);
        currentPulse.set(data.scenarios().get(scenario).pulses().get(iterationKeys.get(index)));
    }
}
