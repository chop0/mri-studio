package ax.xz.mri.ui.menu;

import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import java.util.function.Consumer;

/**
 * Single source of truth for the studio's context-menu items — labels,
 * accelerators, and ordering.
 *
 * <p>HFSS-grade desktop apps use the same words and shortcuts everywhere:
 * "Delete" never becomes "Remove", "Copy" is always {@code ⌘C}, items
 * appear in the same order across every list/tree/canvas. The vocabulary
 * defined here is used by every context-menu contributor in the studio so
 * the menus look like they were drawn by one hand.
 *
 * <p>Usage:
 * <pre>{@code
 * var copy = ContextMenuVocabulary.COPY.item(() -> session.copySelectedClips());
 * menu.getItems().addAll(
 *     ContextMenuVocabulary.CUT.item(() -> ...),
 *     copy,
 *     ContextMenuVocabulary.PASTE.item(() -> ...),
 *     ContextMenuVocabulary.SEPARATOR.separator(),
 *     ContextMenuVocabulary.DELETE.item(() -> ...));
 * }</pre>
 *
 * <p>To omit an item: don't call {@code .item()} on it. To add a custom
 * action that doesn't fit any standard verb, use a plain {@link MenuItem};
 * the rest of the menu still flows through this enum so the chrome stays
 * uniform.
 */
public enum ContextMenuVocabulary {

    // ── Clipboard verbs ────────────────────────────────────────────────────
    CUT             ("Cut",                   "Shortcut+X"),
    COPY            ("Copy",                  "Shortcut+C"),
    PASTE           ("Paste",                 "Shortcut+V"),

    // ── Mutation verbs ─────────────────────────────────────────────────────
    DUPLICATE       ("Duplicate",             "Shortcut+D"),
    DELETE          ("Delete",                "Delete"),
    RENAME          ("Rename…",          "F2"),

    // ── Value-row verbs (PropertySheet rows etc) ───────────────────────────
    RESET_TO_DEFAULT("Reset to Default",      null),
    COPY_VALUE      ("Copy Value",            null),
    PASTE_VALUE     ("Paste Value",           null),

    // ── Plot/canvas verbs ──────────────────────────────────────────────────
    RESET_VIEW      ("Reset View",            null),
    EXPORT_PNG      ("Export as Image…", null),
    COPY_DATA       ("Copy Data to Clipboard", null),

    // ── File / project verbs ───────────────────────────────────────────────
    REVEAL          ("Reveal in Finder",      null),
    PROPERTIES      ("Properties…",      "Shortcut+I"),

    // ── Container verbs ────────────────────────────────────────────────────
    NEW_FOLDER      ("New Folder",            null),
    SELECT_ALL      ("Select All",            "Shortcut+A");

    private final String label;
    private final String accelerator;

    ContextMenuVocabulary(String label, String accelerator) {
        this.label = label;
        this.accelerator = accelerator;
    }

    public String label()       { return label; }
    public String accelerator() { return accelerator; }

    /** Build a fresh {@link MenuItem} bound to the given action. */
    public MenuItem item(Runnable action) {
        var mi = new MenuItem(label);
        if (accelerator != null) {
            mi.setAccelerator(KeyCombination.keyCombination(accelerator));
        }
        if (action != null) mi.setOnAction(e -> action.run());
        return mi;
    }

    /** Build a {@link MenuItem} that's permanently disabled (e.g. when the verb doesn't apply). */
    public MenuItem disabledItem() {
        var mi = item(null);
        mi.setDisable(true);
        return mi;
    }

    /** Convenience: a separator. */
    public static SeparatorMenuItem separator() { return new SeparatorMenuItem(); }

    /**
     * For verbs whose enabled state depends on context (e.g. paste enabled
     * iff clipboard has clips). Wraps {@link #item} with an enable predicate
     * evaluated at construction time.
     */
    public MenuItem item(boolean enabled, Runnable action) {
        var mi = item(action);
        mi.setDisable(!enabled);
        return mi;
    }

    /** Allow caller to consume the {@link MenuItem} for further customisation. */
    public MenuItem item(Runnable action, Consumer<MenuItem> tweak) {
        var mi = item(action);
        tweak.accept(mi);
        return mi;
    }
}
