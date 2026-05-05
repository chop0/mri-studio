package ax.xz.mri.state;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection-based engine for surgically editing immutable record trees.
 *
 * <p>Instance-based (not static) so each {@link UnifiedStateManager} owns its
 * own surgery instance with isolated caches. Caches per-class
 * {@link RecordComponent} arrays and canonical-constructor {@link MethodHandle}s
 * so the hot path is a single map lookup + handle invocation.
 *
 * <p>Reflection goes <em>all the way down</em> — including into sealed
 * interface hierarchies like {@code CircuitComponent}. Editing a single field
 * on a deeply-nested record produces a {@link Mutation} scoped to the deepest
 * field that actually changed. The same machinery powers
 * {@link JacksonRecordModule}.
 */
public final class RecordSurgery {
    private final Map<Class<?>, RecordComponent[]> componentsCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, MethodHandle> ctorCache = new ConcurrentHashMap<>();
    private final MethodHandles.Lookup lookup = MethodHandles.publicLookup();

    /** Component metadata for the given record class, cached. */
    public RecordComponent[] componentsOf(Class<?> recordClass) {
        return componentsCache.computeIfAbsent(recordClass, c -> {
            if (!c.isRecord()) throw new IllegalArgumentException(c + " is not a record");
            return c.getRecordComponents();
        });
    }

    /** Read one component value by name. */
    public Object get(Object record, String fieldName) {
        Objects.requireNonNull(record, "record");
        var rc = component(record.getClass(), fieldName);
        try {
            return rc.getAccessor().invoke(record);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("Failed to read " + fieldName + " on " + record.getClass(), ex);
        }
    }

    /**
     * Construct a new record by replacing one component. Goes through the
     * canonical constructor — record invariants and validation in the
     * compact constructor still fire.
     */
    @SuppressWarnings("unchecked")
    public <R extends Record> R with(R record, String fieldName, Object value) {
        Objects.requireNonNull(record, "record");
        var components = componentsOf(record.getClass());
        var args = new Object[components.length];
        boolean found = false;
        for (int i = 0; i < components.length; i++) {
            if (components[i].getName().equals(fieldName)) {
                args[i] = value;
                found = true;
            } else {
                try {
                    args[i] = components[i].getAccessor().invoke(record);
                } catch (ReflectiveOperationException ex) {
                    throw new RuntimeException("Failed to read " + components[i].getName(), ex);
                }
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Record " + record.getClass().getSimpleName()
                + " has no component named " + fieldName);
        }
        try {
            return (R) canonicalConstructor(record.getClass()).invokeWithArguments(args);
        } catch (Throwable ex) {
            throw new RuntimeException("Failed to construct " + record.getClass().getSimpleName()
                + " with " + fieldName + "=" + value, ex);
        }
    }

    /**
     * Read the value at the given scope, walking from the root.
     *
     * <p>Returns {@code null} if any intermediate path doesn't resolve.
     */
    public Object getAt(Object root, Scope scope) {
        Objects.requireNonNull(scope, "scope");
        if (scope instanceof Scope.Root) return root;
        if (scope instanceof Scope.FieldOf f) {
            var parent = getAt(root, f.parent());
            if (parent == null) return null;
            return get(parent, f.fieldName());
        }
        if (scope instanceof Scope.IndexedItem i) {
            var parent = getAt(root, i.parent());
            if (parent == null) return null;
            var coll = get(parent, i.fieldName());
            if (coll instanceof Map<?, ?> m) return m.get(i.key());
            if (coll instanceof List<?> l) {
                if (i.key() instanceof Integer idx) return idx >= 0 && idx < l.size() ? l.get(idx) : null;
                // Stable-id keyed list: linear scan looking for an element whose id equals key.
                for (var elt : l) {
                    if (elt != null && idMatches(elt, i.key())) return elt;
                }
                return null;
            }
            throw new IllegalStateException("IndexedItem on non-collection field "
                + i.fieldName() + " (got " + (coll == null ? "null" : coll.getClass()) + ")");
        }
        throw new IllegalStateException("Unknown scope: " + scope);
    }

    /**
     * Walk the scope path through a root, replacing the leaf with
     * {@code newValue}. Returns a new root with the change applied.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object rebuild(Object root, Scope scope, Object newValue) {
        Objects.requireNonNull(scope, "scope");
        if (scope instanceof Scope.Root) return newValue;
        if (scope instanceof Scope.FieldOf f) {
            var parent = getAt(root, f.parent());
            if (parent == null) {
                throw new IllegalStateException("Cannot rebuild at " + scope + ": parent is null");
            }
            if (!(parent instanceof Record parentRec)) {
                throw new IllegalStateException("FieldOf scope requires a record parent at "
                    + f.parent() + ", got " + parent.getClass());
            }
            var updated = with(parentRec, f.fieldName(), newValue);
            return rebuild(root, f.parent(), updated);
        }
        if (scope instanceof Scope.IndexedItem i) {
            var parent = getAt(root, i.parent());
            if (parent == null) {
                throw new IllegalStateException("Cannot rebuild at " + scope + ": parent is null");
            }
            if (!(parent instanceof Record parentRec)) {
                throw new IllegalStateException("IndexedItem scope requires a record parent at "
                    + i.parent() + ", got " + parent.getClass());
            }
            var coll = get(parent, i.fieldName());
            Object replaced;
            if (coll instanceof Map<?, ?> m) {
                var copy = new LinkedHashMap<Object, Object>((Map) m);
                if (newValue == null) copy.remove(i.key());
                else copy.put(i.key(), newValue);
                replaced = java.util.Collections.unmodifiableMap(copy);
            } else if (coll instanceof List<?> l) {
                var copy = new ArrayList<Object>((List<Object>) l);
                if (i.key() instanceof Integer idx) {
                    if (newValue == null) copy.remove((int) idx);
                    else if (idx == copy.size()) copy.add(newValue);
                    else copy.set(idx, newValue);
                } else {
                    int found = -1;
                    for (int k = 0; k < copy.size(); k++) {
                        if (copy.get(k) != null && idMatches(copy.get(k), i.key())) { found = k; break; }
                    }
                    if (found < 0) {
                        if (newValue == null) {
                            // Already absent: nothing to do.
                        } else {
                            copy.add(newValue);
                        }
                    } else if (newValue == null) {
                        copy.remove(found);
                    } else {
                        copy.set(found, newValue);
                    }
                }
                replaced = List.copyOf(copy);
            } else {
                throw new IllegalStateException("IndexedItem on non-collection field "
                    + i.fieldName() + " (got " + (coll == null ? "null" : coll.getClass()) + ")");
            }
            var updated = with(parentRec, i.fieldName(), replaced);
            return rebuild(root, i.parent(), updated);
        }
        throw new IllegalStateException("Unknown scope: " + scope);
    }

    /**
     * Walk before/after of the same record type and produce one {@link Mutation}
     * per field that actually differs, scoped to the deepest changed path.
     *
     * <p>Stops descending at scope leaves: primitive arrays, non-record
     * collection elements, and Java built-ins where {@code equals} is the
     * authoritative comparison.
     */
    public List<Mutation> diff(Object before, Object after,
                               Scope basePath, String label,
                               String editorId, Mutation.Category cat) {
        var out = new ArrayList<Mutation>();
        diffInto(before, after, basePath, label, editorId, cat, out);
        return out;
    }

    private void diffInto(Object before, Object after,
                          Scope path, String label, String editorId,
                          Mutation.Category cat, List<Mutation> out) {
        if (Objects.equals(before, after)) return;
        // Records of the same type → recurse component-wise.
        if (before != null && after != null
            && before.getClass() == after.getClass()
            && before.getClass().isRecord()) {
            var components = componentsOf(before.getClass());
            for (var c : components) {
                Object oldV, newV;
                try {
                    oldV = c.getAccessor().invoke(before);
                    newV = c.getAccessor().invoke(after);
                } catch (ReflectiveOperationException ex) {
                    throw new RuntimeException(ex);
                }
                if (Objects.equals(oldV, newV)) continue;
                diffInto(oldV, newV, Scope.field(path, c.getName()), label, editorId, cat, out);
            }
            return;
        }
        // Maps of the same id → diff per-key.
        if (before instanceof Map<?, ?> mb && after instanceof Map<?, ?> ma) {
            var keys = new java.util.LinkedHashSet<Object>();
            keys.addAll(mb.keySet());
            keys.addAll(ma.keySet());
            // Map sits inside a field — the path here represents the field
            // itself; scope per-key is IndexedItem(parent, fieldName, key).
            // Caller passes path = FieldOf(..., fieldName); we build IndexedItem on path.parent().
            if (!(path instanceof Scope.FieldOf field)) {
                // Coarser fallback: emit one mutation for the whole map.
                out.add(new Mutation(path, before, after, label, Instant.now(), editorId, cat));
                return;
            }
            for (var k : keys) {
                var oldV = mb.get(k);
                var newV = ma.get(k);
                if (Objects.equals(oldV, newV)) continue;
                diffInto(oldV, newV,
                    Scope.indexed(field.parent(), field.fieldName(), k),
                    label, editorId, cat, out);
            }
            return;
        }
        // Lists with id-bearing elements → diff per element by id.
        if (before instanceof List<?> lb && after instanceof List<?> la
            && allHaveIdComponent(lb) && allHaveIdComponent(la)) {
            if (!(path instanceof Scope.FieldOf field)) {
                out.add(new Mutation(path, before, after, label, Instant.now(), editorId, cat));
                return;
            }
            var beforeById = byId(lb);
            var afterById = byId(la);
            var keys = new java.util.LinkedHashSet<Object>();
            keys.addAll(beforeById.keySet());
            keys.addAll(afterById.keySet());
            for (var k : keys) {
                var oldV = beforeById.get(k);
                var newV = afterById.get(k);
                if (Objects.equals(oldV, newV)) continue;
                diffInto(oldV, newV,
                    Scope.indexed(field.parent(), field.fieldName(), k),
                    label, editorId, cat, out);
            }
            return;
        }
        // Lists without stable ids are emitted as whole-collection replacements.
        out.add(new Mutation(path, before, after, label, Instant.now(), editorId, cat));
    }

    private boolean allHaveIdComponent(List<?> list) {
        if (list.isEmpty()) return false;
        for (var e : list) {
            if (e == null) return false;
            if (!hasIdComponent(e.getClass())) return false;
        }
        return true;
    }

    private boolean hasIdComponent(Class<?> cls) {
        if (!cls.isRecord()) return false;
        for (var c : componentsOf(cls)) {
            if (c.getName().equals("id")) return true;
        }
        return false;
    }

    private Map<Object, Object> byId(List<?> list) {
        var out = new LinkedHashMap<Object, Object>();
        for (var e : list) {
            if (e == null) continue;
            try {
                Object id = null;
                for (var c : componentsOf(e.getClass())) {
                    if (c.getName().equals("id")) {
                        id = c.getAccessor().invoke(e);
                        break;
                    }
                }
                if (id != null) out.put(id, e);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }
        return out;
    }

    /* ── helpers ──────────────────────────────────────────────────────────── */

    private RecordComponent component(Class<?> cls, String fieldName) {
        for (var c : componentsOf(cls)) {
            if (c.getName().equals(fieldName)) return c;
        }
        throw new IllegalArgumentException("Record " + cls.getSimpleName()
            + " has no component named " + fieldName);
    }

    private MethodHandle canonicalConstructor(Class<?> cls) {
        return ctorCache.computeIfAbsent(cls, c -> {
            var components = componentsOf(c);
            var paramTypes = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) paramTypes[i] = components[i].getType();
            try {
                var ctor = c.getDeclaredConstructor(paramTypes);
                ctor.setAccessible(true);
                return lookup.unreflectConstructor(ctor);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException("No canonical constructor on " + c, ex);
            }
        });
    }

    /**
     * Best-effort id comparison for elements in id-keyed lists.
     * Returns true if {@code element} has an {@code id()} component whose
     * value equals {@code key}, or if {@code element.equals(key)}.
     */
    private boolean idMatches(Object element, Object key) {
        if (Objects.equals(element, key)) return true;
        if (element.getClass().isRecord()) {
            for (var c : componentsOf(element.getClass())) {
                if (c.getName().equals("id")) {
                    try {
                        return Objects.equals(c.getAccessor().invoke(element), key);
                    } catch (ReflectiveOperationException ex) {
                        return false;
                    }
                }
            }
        }
        return false;
    }
}
