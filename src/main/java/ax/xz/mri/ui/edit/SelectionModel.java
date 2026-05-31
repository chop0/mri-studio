package ax.xz.mri.ui.edit;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;

import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * Selection state for the timeline editor — the clip-id specialisation of
 * {@link Selectable}. Holds the set of selected clip ids plus a single
 * primary id, the focus of the inspector and the anchor for shift-extend.
 *
 * <p>The {@link Selectable} default methods cover the standard ops; the few
 * pre-existing call sites that used a slightly different {@code replaceWith}
 * shape are kept here as thin wrappers.
 */
public final class SelectionModel implements Selectable<String> {
    private final ObservableSet<String> selected =
        FXCollections.observableSet(new LinkedHashSet<>());
    private final ObjectProperty<String> primary = new SimpleObjectProperty<>(null);

    public SelectionModel() {
        // Maintain the primary-must-be-in-selection invariant. If an external
        // mutation drops the primary id, re-anchor onto an arbitrary remaining
        // member rather than nulling — the default Selectable.toggle relies on
        // this so the primary survives a "deselect the primary" toggle.
        selected.addListener((javafx.collections.SetChangeListener<String>) c -> {
            String p = primary.get();
            if (p != null && !selected.contains(p)) {
                primary.set(selected.isEmpty() ? null : selected.iterator().next());
            }
        });
    }

    @Override public ObservableSet<String> selected() { return selected; }
    @Override public ObjectProperty<String> primary() { return primary; }

    /** Replace selection with an arbitrary collection. */
    public void replaceWith(Collection<String> ids, String newPrimary) {
        selected.clear();
        selected.addAll(ids);
        primary.set(selected.contains(newPrimary) ? newPrimary
                  : selected.isEmpty() ? null
                  : selected.iterator().next());
    }
}
