package ax.xz.mri.ui.workbench.pane.config;

import javafx.scene.control.ComboBox;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One-way binding helpers for {@link ComboBox}: the combo reflects whatever the
 * model says, the user's pick mutates the model — never both at once.
 *
 * <p>Replaces the brittle {@code setOnAction(null) ... setValue(target) ...
 * setOnAction(old)} swap pattern that used to recur across inspector sections.
 * Instead of suppressing the action handler during programmatic updates, the
 * handler is self-guarded: it short-circuits when the new selection's key
 * already matches the model's current key, so a programmatic
 * {@code setValue(...)} can never recurse into the user-pick callback.
 */
public final class ComboSync {
    private ComboSync() {}

    /**
     * Wire a user-pick handler that's safe against re-entry from programmatic
     * {@code setValue} calls.
     *
     * @param combo       the combo to bind
     * @param keyOf       extracts a stable identity from an item (e.g.
     *                    {@code Track::id}, {@code ClipKind::name}). Lookups
     *                    by key avoid reference-equality pitfalls when items
     *                    are rebuilt fresh from a model.
     * @param currentKey  reads the model's current key. Compared against the
     *                    user's selection inside the action handler — if they
     *                    already match (i.e. the value was just set
     *                    programmatically to mirror the model), the handler
     *                    no-ops instead of recursing.
     * @param onUserPick  invoked only when the user picks an item whose key
     *                    differs from the model's current key.
     */
    public static <T, K> void onUserPick(
        ComboBox<T> combo,
        Function<T, K> keyOf,
        Supplier<K> currentKey,
        Consumer<K> onUserPick
    ) {
        combo.setOnAction(e -> {
            T sel = combo.getValue();
            if (sel == null) return;
            K newKey = keyOf.apply(sel);
            if (Objects.equals(newKey, currentKey.get())) return;
            onUserPick.accept(newKey);
        });
    }

    /** Push the resolved current item into the combo if it has drifted. */
    public static <T> void syncValue(ComboBox<T> combo, T current) {
        if (combo.getValue() != current) combo.setValue(current);
    }
}
