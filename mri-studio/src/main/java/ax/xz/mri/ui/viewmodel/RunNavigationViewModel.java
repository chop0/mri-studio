package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.HashMap;
import java.util.Map;

/** Inspector-owned capture navigation inside an optimisation run. */
public final class RunNavigationViewModel {
    public final ObjectProperty<ProjectNodeId> activeRunId = new SimpleObjectProperty<>();
    public final ObservableList<ProjectNodeId> orderedCaptureIds = FXCollections.observableArrayList();
    public final IntegerProperty activeCaptureIndex = new SimpleIntegerProperty(-1);
    public final ObjectProperty<ProjectNodeId> activeCaptureId = new SimpleObjectProperty<>();
    private final Map<ProjectNodeId, ProjectNodeId> rememberedCaptureByRun = new HashMap<>();

    public RunNavigationViewModel() {
        activeCaptureIndex.addListener((obs, oldValue, newValue) -> syncActiveCaptureFromIndex());
    }

    public void clear() {
        activeRunId.set(null);
        orderedCaptureIds.clear();
        activeCaptureIndex.set(-1);
        activeCaptureId.set(null);
    }

    public void openRun(ProjectRepository repository, ProjectNodeId runId, ProjectNodeId preferredCaptureId) {
        activeRunId.set(runId);
        orderedCaptureIds.setAll(repository.captureIdsForRun(runId));
        if (orderedCaptureIds.isEmpty()) {
            activeCaptureIndex.set(-1);
            activeCaptureId.set(null);
            return;
        }
        ProjectNodeId resolvedCaptureId = preferredCaptureId != null ? preferredCaptureId : rememberedCaptureByRun.get(runId);
        int index = resolvedCaptureId == null ? orderedCaptureIds.size() - 1 : orderedCaptureIds.indexOf(resolvedCaptureId);
        if (index < 0) index = orderedCaptureIds.size() - 1;
        activeCaptureIndex.set(index);
    }

    public void seekCapture(ProjectNodeId captureId) {
        if (captureId == null) return;
        int index = orderedCaptureIds.indexOf(captureId);
        if (index >= 0) {
            activeCaptureIndex.set(index);
        }
    }

    private void syncActiveCaptureFromIndex() {
        if (orderedCaptureIds.isEmpty()) {
            activeCaptureId.set(null);
            return;
        }
        int index = Math.max(0, Math.min(activeCaptureIndex.get(), orderedCaptureIds.size() - 1));
        if (index != activeCaptureIndex.get()) {
            activeCaptureIndex.set(index);
            return;
        }
        var captureId = orderedCaptureIds.get(index);
        activeCaptureId.set(captureId);
        if (activeRunId.get() != null) {
            rememberedCaptureByRun.put(activeRunId.get(), captureId);
        }
    }
}
