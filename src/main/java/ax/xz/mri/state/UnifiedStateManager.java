package ax.xz.mri.state;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.time.Instant;

/**
 * The single mutation point for all persistent project state.
 *
 * <h3>Invariants</h3>
 * <ul>
 *   <li>The {@link #currentProperty()} value is the authoritative
 *       {@link ProjectState} — no other in-memory copy is canonical.</li>
 *   <li>Every change to {@code current} happens through
 *       {@link #dispatch(Mutation)} or a {@link Transaction}'s commit.</li>
 *   <li>Every dispatch records into the undo log; therefore <em>persisted ⇒
 *       undoable</em> by construction.</li>
 *   <li>Reference-integrity validation runs inside dispatch — the same
 *       Mutation's {@code after} carries cascade fix-ups so undo is
 *       atomic.</li>
 *   <li>Autosave is debounced — every dispatch reschedules a write 200ms in
 *       the future.</li>
 * </ul>
 */
public final class UnifiedStateManager {
    private static final int MAX_LOG = 1000;

    private final ObjectProperty<ProjectState> current;
    private final Deque<Mutation> undoLog = new ArrayDeque<>();
    private final Deque<Mutation> redoLog = new ArrayDeque<>();
    private final BooleanProperty canUndo = new SimpleBooleanProperty(false);
    private final BooleanProperty canRedo = new SimpleBooleanProperty(false);
    private final RecordSurgery surgery;
    private final Autosaver autosaver;
    private final Consumer<List<String>> integrityFixSink;

    public UnifiedStateManager(ProjectState initial,
                               RecordSurgery surgery,
                               Autosaver autosaver,
                               Consumer<List<String>> integrityFixSink) {
        this.current = new SimpleObjectProperty<>(initial);
        this.surgery = surgery;
        this.autosaver = autosaver;
        this.integrityFixSink = integrityFixSink == null ? l -> {} : integrityFixSink;
    }

    public RecordSurgery surgery() { return surgery; }
    public Autosaver autosaver()   { return autosaver; }

    public ReadOnlyObjectProperty<ProjectState> currentProperty() {
        return current;
    }

    public ProjectState current() {
        return current.get();
    }

    public ReadOnlyBooleanProperty canUndoProperty() { return canUndo; }
    public ReadOnlyBooleanProperty canRedoProperty() { return canRedo; }

    /** Read the value at the given scope from the current state. */
    @SuppressWarnings("unchecked")
    public <T> T getAt(Scope scope) {
        return (T) surgery.getAt(current.get(), scope);
    }

    /**
     * Apply a mutation, validate referential integrity (eager cascade), append
     * to undo log, schedule autosave. Must be called on the FX thread.
     */
    public void dispatch(Mutation m) {
        ensureFx();
        var before = current.get();
        ProjectState after;
        try {
            after = (ProjectState) surgery.rebuild(before, m.scope(), m.after());
        } catch (Throwable ex) {
            throw new RuntimeException("Failed to apply mutation at " + m.scope(), ex);
        }

        var integrity = RefIntegrity.validate(after);
        var finalState = integrity.state();

        Mutation logged;
        if (integrity.changed()) {
            // Cascade affected scopes other than the original — escalate the
            // recorded mutation to root so undo restores the entire delta in
            // one atomic step.
            logged = new Mutation(Scope.root(), before, finalState,
                m.label(), m.timestamp(), m.editorId(), m.category());
            integrityFixSink.accept(integrity.fixes());
        } else {
            logged = m;
        }

        if (finalState.equals(before)) return; // no-op

        current.set(finalState);
        pushUndo(logged);
        autosaver.schedule(finalState);
    }

    /**
     * Begin a scoped transaction. Mutations whose scope ⊆ the transaction's
     * scope are absorbed; mutations outside the scope dispatch normally.
     */
    public Transaction beginTransaction(String label, String editorId, Scope scope) {
        return new Transaction(this, label, editorId, scope);
    }

    /**
     * Commit a transaction. Receives the captured before-state at the
     * transaction's scope and the current after-state at the same scope.
     */
    void commitTransaction(Scope scope, Object before, Object after, String label, String editorId, Mutation.Category cat) {
        ensureFx();
        if (java.util.Objects.equals(before, after)) return;
        var m = new Mutation(scope, before, after, label,
            Instant.now(), editorId, cat);
        pushUndo(m);
        autosaver.schedule(current.get());
    }

    /** Apply a single in-transaction step that bumps current state without logging. */
    void applyDuringTransaction(Scope scope, Object newValue) {
        ensureFx();
        var before = current.get();
        var after = (ProjectState) surgery.rebuild(before, scope, newValue);
        if (after.equals(before)) return;
        current.set(after);
    }

    /** Undo the most recent mutation matching the predicate. Returns true if one was undone. */
    public boolean undoIn(Predicate<Mutation> filter) {
        ensureFx();
        // Walk from most recent to oldest looking for a match. Mutations that
        // don't match are kept in place (simulating a per-scope stack view).
        var temp = new ArrayDeque<Mutation>();
        Mutation found = null;
        while (!undoLog.isEmpty()) {
            var m = undoLog.pop();
            if (filter.test(m)) {
                found = m;
                break;
            }
            temp.push(m);
        }
        // Restore the unmatched mutations to their original positions.
        while (!temp.isEmpty()) undoLog.push(temp.pop());
        if (found == null) return false;

        // Apply the inverse to the current state.
        var before = current.get();
        var after = (ProjectState) surgery.rebuild(before, found.scope(), found.before());
        current.set(after);
        redoLog.push(found);
        autosaver.schedule(after);
        updateCanUndoRedo();
        return true;
    }

    /** Redo the most recent mutation matching the predicate. Returns true if one was redone. */
    public boolean redoIn(Predicate<Mutation> filter) {
        ensureFx();
        var temp = new ArrayDeque<Mutation>();
        Mutation found = null;
        while (!redoLog.isEmpty()) {
            var m = redoLog.pop();
            if (filter.test(m)) {
                found = m;
                break;
            }
            temp.push(m);
        }
        while (!temp.isEmpty()) redoLog.push(temp.pop());
        if (found == null) return false;

        var before = current.get();
        var after = (ProjectState) surgery.rebuild(before, found.scope(), found.after());
        current.set(after);
        undoLog.push(found);
        trimUndoLog();
        autosaver.schedule(after);
        updateCanUndoRedo();
        return true;
    }

    /* ── filters ──────────────────────────────────────────────────────────── */

    public Predicate<Mutation> withinScope(Scope s) {
        return m -> s.contains(m.scope());
    }

    public Predicate<Mutation> structural() {
        return m -> m.category() == Mutation.Category.STRUCTURAL;
    }

    public Predicate<Mutation> any() {
        return m -> true;
    }

    /* ── lifecycle ────────────────────────────────────────────────────────── */

    /** Replace the entire state — used on project open/close. Clears undo logs. */
    public void replaceState(ProjectState state) {
        ensureFx();
        current.set(state);
        undoLog.clear();
        redoLog.clear();
        updateCanUndoRedo();
    }

    /** Force an immediate flush of pending writes. */
    public void flush() {
        autosaver.flush();
    }

    public java.util.List<Mutation> undoSnapshot() {
        return java.util.List.copyOf(undoLog);
    }

    public java.util.List<Mutation> redoSnapshot() {
        return java.util.List.copyOf(redoLog);
    }

    /* ── helpers ──────────────────────────────────────────────────────────── */

    private void pushUndo(Mutation m) {
        undoLog.push(m);
        trimUndoLog();
        redoLog.clear();
        updateCanUndoRedo();
        autosaver.scheduleLog(undoLog);
    }

    /**
     * Replace the undo log atomically — used by project-open to install a
     * persisted log alongside the freshly-loaded state.
     */
    public void installUndoLog(java.util.List<Mutation> entries) {
        ensureFx();
        undoLog.clear();
        // The persisted log was written most-recent-first via Deque.push;
        // restore that ordering so undo() pulls the newest entry first.
        for (int i = entries.size() - 1; i >= 0; i--) undoLog.push(entries.get(i));
        redoLog.clear();
        trimUndoLog();
        updateCanUndoRedo();
    }

    private void trimUndoLog() {
        while (undoLog.size() > MAX_LOG) undoLog.pollLast();
    }

    private void updateCanUndoRedo() {
        canUndo.set(!undoLog.isEmpty());
        canRedo.set(!redoLog.isEmpty());
    }

    private static void ensureFx() {
        if (!Platform.isFxApplicationThread() && !Boolean.getBoolean("ax.xz.mri.state.bypass-fx-check")) {
            throw new IllegalStateException("UnifiedStateManager mutations must run on the FX thread (got "
                + Thread.currentThread().getName() + ")");
        }
    }
}
