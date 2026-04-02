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

/**
 * Current document/session selection state: file, scenario, iteration, and active pulse.
 *
 * <p>For imported captures, this handles multi-scenario/iteration resolution.
 * For simulation, {@link StudioSession#loadSimulationResult} writes directly to
 * {@link #blochData} and {@link #currentPulse}, bypassing scenario resolution.
 */
public class DocumentSessionViewModel {
    public final ObjectProperty<File> currentFile = new SimpleObjectProperty<>();
    public final ObjectProperty<BlochData> blochData = new SimpleObjectProperty<>();
    public final StringProperty currentScenario = new SimpleStringProperty();
    public final IntegerProperty iterationIndex = new SimpleIntegerProperty(0);
    public final ObservableList<String> scenarioKeys = FXCollections.observableArrayList();
    public final ObservableList<String> iterationKeys = FXCollections.observableArrayList();
    public final ObjectProperty<List<PulseSegment>> currentPulse = new SimpleObjectProperty<>();

    public DocumentSessionViewModel() {
        currentScenario.addListener((obs, oldScenario, newScenario) -> onScenarioChanged());
        iterationIndex.addListener((obs, oldValue, newValue) -> onIterationIndexChanged());
    }

    public void setDocument(File file, BlochData data) {
        currentFile.set(file);
        blochData.set(data);
        resolveScenarioAndIteration(null, null);
    }

    public void showCapture(File file, BlochData data, String scenarioKey, String iterationKey) {
        currentFile.set(file);
        blochData.set(data);
        resolveScenarioAndIteration(scenarioKey, iterationKey);
    }

    public void clearDocument() {
        currentFile.set(null);
        blochData.set(null);
        scenarioKeys.clear();
        iterationKeys.clear();
        currentScenario.set(null);
        currentPulse.set(null);
        iterationIndex.set(0);
    }

    private void resolveScenarioAndIteration(String scenarioHint, String iterationHint) {
        var data = blochData.get();
        if (data == null || data.scenarios() == null || data.scenarios().isEmpty()) {
            scenarioKeys.clear();
            iterationKeys.clear();
            currentPulse.set(null);
            currentScenario.set(null);
            iterationIndex.set(0);
            return;
        }

        var sortedScenarioKeys = data.scenarios().keySet().stream().sorted().toList();
        String resolvedScenario = scenarioHint != null && data.scenarios().containsKey(scenarioHint)
            ? scenarioHint
            : sortedScenarioKeys.getFirst();

        var scenario = data.scenarios().get(resolvedScenario);
        var resolvedIterationKeys = scenario.iterationKeys();

        int resolvedIndex;
        if (iterationHint == null) {
            resolvedIndex = resolvedIterationKeys.isEmpty() ? 0 : resolvedIterationKeys.size() - 1;
        } else {
            resolvedIndex = resolvedIterationKeys.indexOf(iterationHint);
            if (resolvedIndex < 0) resolvedIndex = resolvedIterationKeys.isEmpty() ? 0 : resolvedIterationKeys.size() - 1;
        }

        List<PulseSegment> resolvedPulse = resolvedIterationKeys.isEmpty()
            ? null
            : scenario.pulses().get(resolvedIterationKeys.get(resolvedIndex));

        scenarioKeys.setAll(sortedScenarioKeys);
        currentScenario.set(resolvedScenario);
        iterationKeys.setAll(resolvedIterationKeys);
        iterationIndex.set(resolvedIndex);
        currentPulse.set(resolvedPulse);
    }

    private void onScenarioChanged() {
        var data = blochData.get();
        var scenario = currentScenario.get();
        if (data == null || scenario == null || !data.scenarios().containsKey(scenario)) {
            iterationKeys.clear();
            currentPulse.set(null);
            return;
        }

        iterationKeys.setAll(data.scenarios().get(scenario).iterationKeys());
        iterationIndex.set(Math.max(0, iterationKeys.size() - 1));
        resolvePulseFromCurrentState();
    }

    private void onIterationIndexChanged() {
        resolvePulseFromCurrentState();
    }

    private void resolvePulseFromCurrentState() {
        var data = blochData.get();
        var scenario = currentScenario.get();
        if (data == null || scenario == null || iterationKeys.isEmpty()) {
            currentPulse.set(null);
            return;
        }

        int index = Math.max(0, Math.min(iterationIndex.get(), iterationKeys.size() - 1));
        if (index != iterationIndex.get()) {
            iterationIndex.set(index);
            return;
        }
        currentPulse.set(data.scenarios().get(scenario).pulses().get(iterationKeys.get(index)));
    }
}
