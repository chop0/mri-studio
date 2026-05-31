package ax.xz.mri.ui.model;

import ax.xz.mri.ui.edit.Selectable;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;

import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * Shared selection state for geometry, sphere, plots, and the points browser.
 * The {@link IsochromatId} specialisation of {@link Selectable}.
 */
public class IsochromatSelectionModel implements Selectable<IsochromatId> {
    public final ObservableSet<IsochromatId> selectedIds =
        FXCollections.observableSet(new LinkedHashSet<>());
    public final ObjectProperty<IsochromatId> primarySelectedId = new SimpleObjectProperty<>();

    @Override public ObservableSet<IsochromatId> selected() { return selectedIds; }
    @Override public ObjectProperty<IsochromatId> primary() { return primarySelectedId; }

    /** Replace the selection — primary becomes the first id, or null if empty. */
    public void setAll(Collection<IsochromatId> ids) {
        selectedIds.clear();
        selectedIds.addAll(ids);
        primarySelectedId.set(selectedIds.stream().findFirst().orElse(null));
    }

    /** Drop ids no longer present in the live set; re-anchor primary if needed. */
    public void removeMissing(Collection<IsochromatId> existingIds) {
        selectedIds.retainAll(existingIds);
        if (primarySelectedId.get() != null && !selectedIds.contains(primarySelectedId.get())) {
            primarySelectedId.set(selectedIds.stream().findFirst().orElse(null));
        }
    }

    /** Legacy single-selection setter — equivalent to {@link #selectOnly}. */
    public void setSingle(IsochromatId id) { selectOnly(id); }
}
