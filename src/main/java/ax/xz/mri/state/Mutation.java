package ax.xz.mri.state;

import java.time.Instant;
import java.util.Objects;

/**
 * A unit of change in the unified state model.
 *
 * <p>Every persistent edit flows through a {@code Mutation}. The
 * {@link UnifiedStateManager} applies it (replacing {@link #before} with
 * {@link #after} at {@link #scope}), appends it to the undo log, schedules
 * autosave, and clears the redo log.
 *
 * @param scope     where in the {@link ProjectState} tree this lands
 * @param before    value at that scope before the change (a record or scalar)
 * @param after     value at that scope after the change
 * @param label     short, user-facing description (shown in undo/redo menus)
 * @param timestamp when the mutation was created (FX-thread time)
 * @param editorId  identifier of the editor that originated the mutation —
 *                  used by transactions and telemetry. May be {@code null} for
 *                  programmatic mutations not associated with a specific editor
 *                  (e.g. project import, reference-integrity cascades).
 * @param category  whether this is a structural change (add/remove/rename of
 *                  top-level documents) or a content change inside a document
 */
public record Mutation(
    Scope scope,
    Object before,
    Object after,
    String label,
    Instant timestamp,
    String editorId,
    Category category
) {

    public enum Category { STRUCTURAL, CONTENT }

    public Mutation {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(category, "category");
    }

    /** Construct a content mutation at the current instant, no editor id. */
    public static Mutation content(Scope scope, Object before, Object after, String label) {
        return new Mutation(scope, before, after, label, Instant.now(), null, Category.CONTENT);
    }

    /** Construct a content mutation at the current instant, tagged with the given editor id. */
    public static Mutation content(Scope scope, Object before, Object after, String label, String editorId) {
        return new Mutation(scope, before, after, label, Instant.now(), editorId, Category.CONTENT);
    }

    /** Construct a structural mutation at the current instant. */
    public static Mutation structural(Scope scope, Object before, Object after, String label) {
        return new Mutation(scope, before, after, label, Instant.now(), null, Category.STRUCTURAL);
    }

    /** Inverse of this mutation — swaps before/after, keeps everything else. */
    public Mutation inverse() {
        return new Mutation(scope, after, before, label, timestamp, editorId, category);
    }

    /** Replace the {@code after} value (used by transaction coalescing). */
    public Mutation withAfter(Object newAfter) {
        return new Mutation(scope, before, newAfter, label, timestamp, editorId, category);
    }
}
