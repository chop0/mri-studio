package ax.xz.mri.state;

import java.util.Objects;

/**
 * A path into the {@link ProjectState} record tree.
 *
 * <p>Represents the location where a {@link Mutation} happens. Editors filter
 * undo/redo by {@link #contains(Scope)} so Ctrl+Z inside the schematic editor
 * only walks back changes made within that circuit's scope.
 *
 * <p>{@link IndexedItem#key()} must be a <em>stable</em> identifier
 * ({@code ProjectNodeId}, {@code ComponentId}, wire/track/clip {@code String id})
 * — never an integer index, since index positions shift when collections are
 * reordered or trimmed and would invalidate the scope.
 */
public sealed interface Scope {

    /** Identity of the root — the entire {@link ProjectState}. */
    record Root() implements Scope {
        @Override public Scope parent() { return null; }
        @Override public String toString() { return "/"; }
    }

    /** A named record component on the parent scope (e.g. {@code circuits}). */
    record FieldOf(Scope parent, String fieldName) implements Scope {
        public FieldOf {
            Objects.requireNonNull(parent, "parent");
            Objects.requireNonNull(fieldName, "fieldName");
        }
        @Override public String toString() { return parent + "/" + fieldName; }
    }

    /**
     * An entry within a collection-valued field on the parent scope, addressed
     * by stable id key.
     *
     * @param parent    enclosing scope (typically a {@link FieldOf} pointing
     *                  at the collection's containing record, or {@link Root})
     * @param fieldName name of the collection-valued component on the parent
     * @param key       stable identifier (id object) of the element
     */
    record IndexedItem(Scope parent, String fieldName, Object key) implements Scope {
        public IndexedItem {
            Objects.requireNonNull(parent, "parent");
            Objects.requireNonNull(fieldName, "fieldName");
            Objects.requireNonNull(key, "key");
        }
        @Override public String toString() { return parent + "/" + fieldName + "[" + key + "]"; }
    }

    /** The enclosing scope, or {@code null} for {@link Root}. */
    Scope parent();

    /** Returns {@code true} iff {@code other} equals this scope or is a descendant of it. */
    default boolean contains(Scope other) {
        if (other == null) return false;
        for (Scope s = other; s != null; s = s.parent()) {
            if (this.equals(s)) return true;
        }
        return false;
    }

    /** Walks the chain of parents from this scope up to the root and returns the depth. */
    default int depth() {
        int d = 0;
        for (Scope s = this; s != null; s = s.parent()) d++;
        return d - 1; // Root is depth 0
    }

    /* ── Factory helpers ──────────────────────────────────────────────────── */

    static Scope root() { return new Root(); }

    static Scope field(Scope parent, String fieldName) {
        return new FieldOf(parent, fieldName);
    }

    static Scope indexed(Scope parent, String fieldName, Object key) {
        return new IndexedItem(parent, fieldName, key);
    }

    /** Convenience: {@code root/fieldName}. */
    static Scope rootField(String fieldName) {
        return field(root(), fieldName);
    }

    /** Convenience: {@code root/fieldName[key]}. */
    static Scope rootIndexed(String fieldName, Object key) {
        return indexed(root(), fieldName, key);
    }
}
