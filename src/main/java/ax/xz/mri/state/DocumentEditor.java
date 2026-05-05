package ax.xz.mri.state;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.util.function.UnaryOperator;

/**
 * The single editor abstraction over a sub-tree of {@link ProjectState}.
 *
 * <p>Replaces the per-editor {@code Deque<Snapshot>} undo systems. Each
 * editor instance is parameterised over its document type {@code T} and
 * its {@link Scope} in the project tree. Calls to {@link #apply} land as
 * {@link Mutation}s in the global undo log; {@link #undo} / {@link #redo}
 * walk the log filtered to this scope.
 */
public final class DocumentEditor<T extends Record> {

    private final UnifiedStateManager state;
    private final Scope scope;
    private final String editorId;
    private final Class<T> type;
    private final ReadOnlyObjectWrapper<T> value;

    public DocumentEditor(UnifiedStateManager state, Scope scope, String editorId, Class<T> type) {
        this.state = state;
        this.scope = scope;
        this.editorId = editorId;
        this.type = type;
        this.value = new ReadOnlyObjectWrapper<>(readFromState());
        // Re-read on every state change (covers both our edits and external ones).
        state.currentProperty().addListener((obs, o, n) -> value.set(readFromState()));
    }

    public Scope scope() { return scope; }
    public String editorId() { return editorId; }

    public ReadOnlyObjectProperty<T> valueProperty() {
        return value.getReadOnlyProperty();
    }

    public T value() {
        return value.get();
    }

    /**
     * Apply a typed delta. The diff between before and after is recorded as
     * one or more {@link Mutation}s and applied to the global state.
     *
     * <p>If the result equals the current value, it's a no-op (no log entry).
     */
    public void apply(UnaryOperator<T> delta, String label) {
        var before = value();
        var after = delta.apply(before);
        if (after == null || after.equals(before)) return;
        // For the editor abstraction, emit a single coarse Mutation at the
        // editor's scope. Finer-grained per-field diffs are an opt-in via
        // applyFineGrained — useful for property-grid edits where each row
        // edits exactly one field.
        var m = Mutation.content(scope, before, after, label, editorId);
        state.dispatch(m);
    }

    /**
     * Apply a typed delta and emit fine-grained mutations (one per field that
     * actually changed). Use this when the editor mutates one field at a time
     * (e.g. property-grid inspectors); the resulting undo entries scope to
     * the deepest changed path.
     */
    public void applyFineGrained(UnaryOperator<T> delta, String label) {
        var before = value();
        var after = delta.apply(before);
        if (after == null || after.equals(before)) return;
        var muts = state.surgery().diff(before, after, scope, label, editorId, Mutation.Category.CONTENT);
        for (var m : muts) state.dispatch(m);
    }

    /**
     * Begin a scoped transaction at this editor's scope. The transaction
     * coalesces in-scope mutations and emits ONE undo entry on commit.
     */
    public Transaction beginTransaction(String label) {
        return state.beginTransaction(label, editorId, scope);
    }

    public void undo() { state.undoIn(state.withinScope(scope)); }
    public void redo() { state.redoIn(state.withinScope(scope)); }

    public ReadOnlyBooleanProperty canUndoProperty() { return state.canUndoProperty(); }
    public ReadOnlyBooleanProperty canRedoProperty() { return state.canRedoProperty(); }

    @SuppressWarnings("unchecked")
    private T readFromState() {
        var v = state.surgery().getAt(state.current(), scope);
        if (v == null) return null;
        if (!type.isInstance(v)) {
            throw new IllegalStateException("Value at " + scope + " is " + v.getClass()
                + ", expected " + type.getName());
        }
        return (T) v;
    }
}
