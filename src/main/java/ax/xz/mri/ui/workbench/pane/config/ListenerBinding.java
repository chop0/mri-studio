package ax.xz.mri.ui.workbench.pane.config;

import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

/**
 * Detachable handle for a registered listener.
 *
 * <p>Use to track listeners attached during a section's build and remove them
 * all at once when the section is rebuilt or disposed — without each call
 * site reinventing its own cleanup list.
 *
 * <p>This is a {@link FunctionalInterface}, so call sites can return a lambda
 * for unusual collection types (e.g. {@code ObservableSet}) directly. For the
 * common cases ({@link Observable}, {@link ObservableList}) use the static
 * factories.
 */
@FunctionalInterface
public interface ListenerBinding {
    void detach();

    /** Bind an {@link InvalidationListener} (or {@code ChangeListener<T>}) to a generic {@link Observable}. */
    static ListenerBinding of(Observable observable, InvalidationListener listener) {
        return () -> observable.removeListener(listener);
    }

    /** Bind a {@link ListChangeListener} to an {@link ObservableList}. */
    static <T> ListenerBinding ofList(ObservableList<T> list, ListChangeListener<? super T> listener) {
        return () -> list.removeListener(listener);
    }
}
