package ax.xz.mri.state;

import java.util.function.UnaryOperator;

/**
 * Scoped, coalescing transaction.
 *
 * <p>Editor pattern (e.g. dragging a circuit component):
 * <pre>{@code
 *   try (var tx = state.beginTransaction("Move R3", "schematic", componentScope)) {
 *       // many intermediate apply() calls during the drag
 *       tx.apply(...);
 *       tx.apply(...);
 *       tx.apply(...);
 *   } // close() commits as one Mutation
 * }</pre>
 *
 * <p>Only mutations <em>within the transaction's scope</em> are absorbed.
 * Mutations outside the scope (e.g. a background simulation completing
 * mid-drag) dispatch normally on the manager and never get folded into the
 * transaction's single undo entry.
 */
public final class Transaction implements AutoCloseable {

    private final UnifiedStateManager manager;
    private final String label;
    private final String editorId;
    private final Scope scope;
    private final Object beforeValue;     // captured at transaction start
    private final Mutation.Category category;
    private boolean closed = false;
    private boolean aborted = false;

    Transaction(UnifiedStateManager manager, String label, String editorId, Scope scope) {
        this.manager = manager;
        this.label = label;
        this.editorId = editorId;
        this.scope = scope;
        this.beforeValue = manager.surgery().getAt(manager.current(), scope);
        this.category = Mutation.Category.CONTENT;
    }

    public Scope scope() { return scope; }

    /**
     * Apply a typed delta to the value at this transaction's scope.
     * Updates the live state without logging an undo entry.
     */
    @SuppressWarnings("unchecked")
    public <T> void apply(UnaryOperator<T> delta) {
        if (closed) throw new IllegalStateException("Transaction already closed");
        var current = (T) manager.surgery().getAt(manager.current(), scope);
        var next = delta.apply(current);
        manager.applyDuringTransaction(scope, next);
    }

    /**
     * Set the value at this transaction's scope directly. Updates the live
     * state without logging an undo entry.
     */
    public void set(Object value) {
        if (closed) throw new IllegalStateException("Transaction already closed");
        manager.applyDuringTransaction(scope, value);
    }

    /** Abort the transaction — restores the captured before-value, no log entry. */
    public void abort() {
        if (closed) return;
        aborted = true;
        manager.applyDuringTransaction(scope, beforeValue);
        closed = true;
    }

    /** Commit explicitly — emits ONE Mutation for the net change. */
    public void commit() {
        if (closed) return;
        if (aborted) { closed = true; return; }
        var afterValue = manager.surgery().getAt(manager.current(), scope);
        manager.commitTransaction(scope, beforeValue, afterValue, label, editorId, category);
        closed = true;
    }

    @Override
    public void close() {
        if (!closed) commit();
    }
}
