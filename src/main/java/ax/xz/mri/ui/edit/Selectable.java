package ax.xz.mri.ui.edit;

import javafx.beans.property.ObjectProperty;
import javafx.collections.ObservableSet;

/**
 * Generalised selection state for any UI surface that lets users pick one or
 * more items: clips on the timeline, components on the schematic, isochromat
 * points on the sphere, project nodes in the explorer, etc.
 *
 * <p>Two observables: a multi-id {@link #selected} set and a single
 * {@link #primary} (the focus of the inspector / anchor for shift-extend).
 * The primary must always be either {@code null} or a member of selected;
 * implementations enforce that invariant.
 *
 * <p>Designed to be paired with {@link ClipboardChannel} and
 * {@link SelectionContext} so cut/copy/paste/delete work uniformly across
 * every selectable surface, with nested panes consuming events first via
 * natural JavaFX event bubbling.
 */
public interface Selectable<T> {
    ObservableSet<T> selected();
    ObjectProperty<T> primary();

    default boolean isSelected(T id) { return id != null && selected().contains(id); }
    default boolean isPrimary(T id)  { return id != null && id.equals(primary().get()); }

    default void selectOnly(T id) {
        selected().clear();
        if (id != null) {
            selected().add(id);
            primary().set(id);
        } else {
            primary().set(null);
        }
    }

    default void add(T id) {
        if (id == null) return;
        selected().add(id);
        if (primary().get() == null) primary().set(id);
    }

    default void toggle(T id) {
        if (id == null) return;
        if (selected().remove(id)) {
            if (id.equals(primary().get())) {
                primary().set(selected().isEmpty() ? null : selected().iterator().next());
            }
        } else {
            selected().add(id);
            primary().set(id);
        }
    }

    default void clear() {
        selected().clear();
        primary().set(null);
    }
}
