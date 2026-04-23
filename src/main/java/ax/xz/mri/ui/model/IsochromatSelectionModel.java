package ax.xz.mri.ui.model;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;

import java.util.Collection;
import java.util.LinkedHashSet;

/** Shared selection state for geometry, sphere, plots, and the points browser. */
public class IsochromatSelectionModel {
    public final ObservableSet<IsochromatId> selectedIds =
        FXCollections.observableSet(new LinkedHashSet<>());
    public final ObjectProperty<IsochromatId> primarySelectedId = new SimpleObjectProperty<>();

    public void setSingle(IsochromatId id) {
        selectedIds.clear();
        if (id != null) selectedIds.add(id);
        primarySelectedId.set(id);
    }

    public void toggle(IsochromatId id) {
        if (id == null) return;
        if (selectedIds.contains(id)) {
            selectedIds.remove(id);
            if (id.equals(primarySelectedId.get())) {
                primarySelectedId.set(selectedIds.stream().findFirst().orElse(null));
            }
        } else {
            selectedIds.add(id);
            primarySelectedId.set(id);
        }
    }

    public void setAll(Collection<IsochromatId> ids) {
        selectedIds.clear();
        selectedIds.addAll(ids);
        primarySelectedId.set(selectedIds.stream().findFirst().orElse(null));
    }

    public boolean isSelected(IsochromatId id) {
        return id != null && selectedIds.contains(id);
    }

    public void removeMissing(Collection<IsochromatId> existingIds) {
        selectedIds.retainAll(existingIds);
        if (primarySelectedId.get() != null && !selectedIds.contains(primarySelectedId.get())) {
            primarySelectedId.set(selectedIds.stream().findFirst().orElse(null));
        }
    }

    public void clear() {
        selectedIds.clear();
        primarySelectedId.set(null);
    }
}
