package ax.xz.mri.ui.edit;

import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Aggregates a {@link Selectable}, a {@link ClipboardChannel}, and the domain
 * actions (delete, duplicate, paste, resolve id→entity) into one drop-in
 * cut/copy/paste/delete handler that any editor pane can attach to itself
 * with a single line.
 *
 * <p>Two type parameters: {@code ID} is the selection key (clip id, component
 * id, isochromat id), {@code WIRE} is the serialisable entity type written
 * to the clipboard. They differ because surfaces select by lightweight ids
 * but copy full record snapshots; the {@code resolver} bridges them.
 *
 * <p><b>Handler resolution via JavaFX event bubbling — no Scene filter.</b>
 * {@link #attachTo} registers a {@code KEY_PRESSED} handler on the node it's
 * given, not the Scene. When the user presses Cmd+X/C/V/Delete, JavaFX fires
 * the event at the focus owner; the event bubbles child → parent. The
 * lowest-level node with a {@code SelectionContext} whose handler can act —
 * non-empty selection for cut/copy/delete, matching clipboard format for
 * paste — calls {@code e.consume()} and bubbling stops. If a node can't
 * handle the event, it doesn't consume; the event bubbles up to the next
 * pane to be asked the same question.
 *
 * <p>This is the "ask each element if it can handle this" semantic the
 * project-wide cut/copy/paste contract requires. A schematic-pane child
 * gets first dibs on Cmd+V because its handler runs before the parent
 * sequence-editor's; if there are no components on the clipboard the event
 * bubbles up and the sequence editor's handler tries to paste clips.
 */
public final class SelectionContext<ID, WIRE> {
    private static final KeyCombination CUT       = new KeyCodeCombination(KeyCode.X, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination COPY      = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination PASTE     = new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination DUPLICATE = new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination SELECT_ALL= new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN);

    private final Selectable<ID> selection;
    private final ClipboardChannel<WIRE> channel;
    /** id → wire-type entity for serialisation. {@code null} for ids that no longer resolve. */
    private final Function<ID, WIRE> resolver;
    /** Apply paste payload to the surface. */
    private final Consumer<List<WIRE>> paster;
    /** Delete the given ids from the surface. */
    private final Consumer<Set<ID>> deleter;
    /** Duplicate the given ids. May be {@code null} if duplicate is unsupported. */
    private final Consumer<Set<ID>> duplicator;
    /** Replace the selection with every element on the surface. May be {@code null}. */
    private final Runnable selectAll;

    public SelectionContext(
            Selectable<ID> selection,
            ClipboardChannel<WIRE> channel,
            Function<ID, WIRE> resolver,
            Consumer<List<WIRE>> paster,
            Consumer<Set<ID>> deleter,
            Consumer<Set<ID>> duplicator,
            Runnable selectAll) {
        this.selection = selection;
        this.channel = channel;
        this.resolver = resolver;
        this.paster = paster;
        this.deleter = deleter;
        this.duplicator = duplicator;
        this.selectAll = selectAll;
    }

    /** Register the keyboard handler on the given node. Subsequent ancestor
     *  contexts are tried only if this one's handler doesn't consume. */
    public void attachTo(Node node) {
        node.addEventHandler(KeyEvent.KEY_PRESSED, this::handle);
    }

    private void handle(KeyEvent e) {
        if (e.getTarget() instanceof javafx.scene.control.TextInputControl) return;
        if (CUT.match(e))            { if (cut())       e.consume(); }
        else if (COPY.match(e))      { if (copy())      e.consume(); }
        else if (PASTE.match(e))     { if (paste())     e.consume(); }
        else if (DUPLICATE.match(e)) { if (duplicate()) e.consume(); }
        else if (SELECT_ALL.match(e)){ if (selectAll()) e.consume(); }
        else if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
            if (delete()) e.consume();
        }
    }

    public boolean copy() {
        var ids = Set.copyOf(selection.selected());
        if (ids.isEmpty()) return false;
        var payload = serialise(ids);
        return !payload.isEmpty() && channel.put(payload);
    }

    public boolean cut() {
        if (!copy()) return false;
        deleter.accept(Set.copyOf(selection.selected()));
        return true;
    }

    public boolean paste() {
        if (paster == null || !channel.hasContent()) return false;
        var items = channel.peek();
        if (items.isEmpty()) return false;
        paster.accept(items);
        return true;
    }

    public boolean delete() {
        var ids = Set.copyOf(selection.selected());
        if (ids.isEmpty()) return false;
        deleter.accept(ids);
        return true;
    }

    public boolean duplicate() {
        if (duplicator == null) return false;
        var ids = Set.copyOf(selection.selected());
        if (ids.isEmpty()) return false;
        duplicator.accept(ids);
        return true;
    }

    public boolean selectAll() {
        if (selectAll == null) return false;
        selectAll.run();
        return true;
    }

    private List<WIRE> serialise(Set<ID> ids) {
        var out = new java.util.ArrayList<WIRE>(ids.size());
        for (var id : ids) {
            var resolved = resolver.apply(id);
            if (resolved != null) out.add(resolved);
        }
        return out;
    }
}
