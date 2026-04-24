package ax.xz.mri.ui.workbench.pane.schematic;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentPosition;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.Wire;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Mutable editing state for one circuit document.
 *
 * <p>{@link #current} is the canonical {@link CircuitDocument}; every
 * mutation replaces it with a new immutable record and bumps
 * {@link #revision} so UI listeners refresh. Selection (component or wire)
 * is tracked here too so the canvas and inspector can coordinate without
 * owning separate sources of truth.
 */
public final class CircuitEditSession {
    private static final int MAX_UNDO = 100;

    public final ObjectProperty<CircuitDocument> current = new SimpleObjectProperty<>();
    public final IntegerProperty revision = new SimpleIntegerProperty(0);
    public final ObservableSet<ComponentId> selectedComponents = FXCollections.observableSet(new LinkedHashSet<>());
    public final ObservableSet<String> selectedWires = FXCollections.observableSet(new LinkedHashSet<>());
    public final StringProperty statusMessage = new SimpleStringProperty("");

    private final java.util.Deque<CircuitDocument> undoStack = new java.util.ArrayDeque<>();
    private final java.util.Deque<CircuitDocument> redoStack = new java.util.ArrayDeque<>();

    public CircuitEditSession(CircuitDocument document) {
        current.set(document);
    }

    public CircuitDocument doc() { return current.get(); }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    public void apply(UnaryOperator<CircuitDocument> delta) {
        var snapshot = doc();
        var next = delta.apply(snapshot);
        if (next != null && !next.equals(snapshot)) {
            pushUndo(snapshot);
            current.set(next);
            revision.set(revision.get() + 1);
        }
    }

    /**
     * Replace the whole document in-place as a user edit. Pushes an undo
     * snapshot so the change is reversible, same as a single
     * {@link #apply(UnaryOperator)}. Used by batch operations like
     * {@link #deleteSelection()} that rebuild the document in one step.
     */
    public void replaceDocument(CircuitDocument next) {
        var snapshot = doc();
        if (!next.equals(snapshot)) {
            pushUndo(snapshot);
            current.set(next);
            revision.set(revision.get() + 1);
        }
    }

    /**
     * External load — blow away undo history and install the document as the
     * new baseline. Use this on project open, never for user edits.
     */
    public void loadDocument(CircuitDocument next) {
        undoStack.clear();
        redoStack.clear();
        current.set(next);
        selectedComponents.clear();
        selectedWires.clear();
        revision.set(revision.get() + 1);
    }

    public void undo() {
        if (undoStack.isEmpty()) return;
        var snapshot = undoStack.pop();
        redoStack.push(doc());
        current.set(snapshot);
        selectedComponents.clear();
        selectedWires.clear();
        revision.set(revision.get() + 1);
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        var snapshot = redoStack.pop();
        undoStack.push(doc());
        current.set(snapshot);
        selectedComponents.clear();
        selectedWires.clear();
        revision.set(revision.get() + 1);
    }

    private void pushUndo(CircuitDocument previous) {
        undoStack.push(previous);
        while (undoStack.size() > MAX_UNDO) undoStack.removeLast();
        redoStack.clear();
    }

    public void selectOnly(ComponentId id) {
        selectedWires.clear();
        selectedComponents.clear();
        if (id != null) selectedComponents.add(id);
    }

    public void selectWireOnly(String wireId) {
        selectedComponents.clear();
        selectedWires.clear();
        if (wireId != null) selectedWires.add(wireId);
    }

    public void clearSelection() {
        selectedComponents.clear();
        selectedWires.clear();
    }

    public void deleteSelection() {
        var doc = doc();
        var toRemove = new ArrayList<>(selectedComponents);
        var wiresToRemove = new ArrayList<>(selectedWires);
        for (var id : toRemove) doc = doc.removeComponent(id);
        for (var id : wiresToRemove) doc = doc.removeWire(id);
        replaceDocument(doc);
    }

    public void addComponent(CircuitComponent component, ComponentPosition position) {
        var existing = new java.util.HashSet<String>();
        for (var c : doc().components()) existing.add(c.name());
        var withUniqueName = existing.contains(component.name())
            ? component.withName(uniqueName(existing, component.name()))
            : component;
        var finalPosition = (position == null || !position.id().equals(withUniqueName.id()))
            ? (position == null ? null
                : new ComponentPosition(withUniqueName.id(), position.x(), position.y(), position.rotationQuarters()))
            : position;
        apply(doc -> doc.addComponent(withUniqueName, finalPosition));
        selectOnly(withUniqueName.id());
    }

    public void replaceComponent(CircuitComponent updated) {
        apply(doc -> doc.replaceComponent(updated));
    }

    public void moveComponent(ComponentId id, double newX, double newY) {
        apply(doc -> {
            var existing = doc.layout().positionOf(id).orElse(null);
            int rot = existing == null ? 0 : existing.rotationQuarters();
            boolean mirrored = existing != null && existing.mirrored();
            return doc.withLayout(doc.layout().with(new ComponentPosition(id, newX, newY, rot, mirrored)));
        });
    }

    /** Rotate every selected component 90 degrees clockwise. */
    public void rotateSelection() {
        if (selectedComponents.isEmpty()) return;
        apply(doc -> {
            var layout = doc.layout();
            for (var id : selectedComponents) {
                var pos = layout.positionOf(id).orElse(null);
                if (pos != null) layout = layout.with(pos.withRotationQuarters(pos.rotationQuarters() + 1));
            }
            return doc.withLayout(layout);
        });
    }

    /** Flip every selected component horizontally. */
    public void mirrorSelection() {
        if (selectedComponents.isEmpty()) return;
        apply(doc -> {
            var layout = doc.layout();
            for (var id : selectedComponents) {
                var pos = layout.positionOf(id).orElse(null);
                if (pos != null) layout = layout.with(pos.withMirrored(!pos.mirrored()));
            }
            return doc.withLayout(layout);
        });
    }

    public void addWire(ComponentTerminal from, ComponentTerminal to) {
        var wire = new Wire("wire-" + UUID.randomUUID(), from, to);
        apply(doc -> doc.addWire(wire));
    }

    public boolean isWireBetween(ComponentTerminal a, ComponentTerminal b) {
        for (var w : doc().wires()) {
            if ((w.from().equals(a) && w.to().equals(b)) || (w.from().equals(b) && w.to().equals(a))) {
                return true;
            }
        }
        return false;
    }

    public ComponentPosition positionOf(ComponentId id) {
        return doc().layout().positionOf(id).orElse(new ComponentPosition(id, 0, 0, 0));
    }

    public CircuitComponent componentAt(ComponentId id) {
        return doc().component(id).orElse(null);
    }

    public List<Wire> wiresOnComponent(ComponentId id) {
        var out = new ArrayList<Wire>();
        for (var w : doc().wires()) {
            if (w.from().componentId().equals(id) || w.to().componentId().equals(id)) out.add(w);
        }
        return out;
    }

    /**
     * Duplicate the selected components (not wires) and offset them by
     * {@code (dx, dy)} in layout coordinates. Wires between selected
     * components are preserved with fresh ids; names are rewritten to remain
     * unique. The clones become the new selection.
     */
    public void duplicateSelection(double dx, double dy) {
        if (selectedComponents.isEmpty()) return;
        var doc = doc();
        var oldToNew = new java.util.HashMap<ComponentId, ComponentId>();
        var additions = new ArrayList<CircuitComponent>();
        var positions = new ArrayList<ComponentPosition>();
        var existingNames = new java.util.HashSet<String>();
        for (var c : doc.components()) existingNames.add(c.name());

        for (var id : selectedComponents) {
            var original = doc.component(id).orElse(null);
            if (original == null) continue;
            var cloneId = new ComponentId(original.id().value() + "-copy-" + UUID.randomUUID());
            String cloneName = uniqueName(existingNames, original.name());
            existingNames.add(cloneName);
            var clone = original.withName(cloneName);
            clone = withId(clone, cloneId);
            oldToNew.put(original.id(), cloneId);
            additions.add(clone);
            var pos = doc.layout().positionOf(original.id()).orElse(null);
            if (pos != null) {
                positions.add(new ComponentPosition(cloneId, pos.x() + dx, pos.y() + dy, pos.rotationQuarters()));
            }
        }

        for (var addition : additions) doc = doc.addComponent(addition, null);
        var layout = doc.layout();
        for (var p : positions) layout = layout.with(p);
        doc = doc.withLayout(layout);

        for (var w : doc().wires()) {
            var fromClone = oldToNew.get(w.from().componentId());
            var toClone = oldToNew.get(w.to().componentId());
            if (fromClone != null && toClone != null) {
                doc = doc.addWire(new Wire("wire-" + UUID.randomUUID(),
                    new ComponentTerminal(fromClone, w.from().port()),
                    new ComponentTerminal(toClone, w.to().port())));
            }
        }

        replaceDocument(doc);
        selectedComponents.clear();
        selectedComponents.addAll(oldToNew.values());
    }

    // ─── Clipboard: cut / copy / paste ────────────────────────────────────

    /**
     * In-memory snapshot of a selection the user cut or copied. Stashes the
     * component data, its layout position, and any wires that connect two
     * clipboard components (so pasting a sub-circuit keeps its internal
     * wiring intact). Cross-boundary wires — ones that go from a clipboard
     * component to something not in the selection — are dropped, because
     * there's no target to hook them up to on the paste side.
     */
    public record Clipboard(
        List<CircuitComponent> components,
        List<ComponentPosition> positions,
        List<Wire> internalWires
    ) {
        public boolean isEmpty() { return components.isEmpty(); }
    }

    private Clipboard clipboard = new Clipboard(List.of(), List.of(), List.of());

    public boolean clipboardIsEmpty() { return clipboard.isEmpty(); }

    /** Current clipboard snapshot — read-only view for the canvas's paste-as-placement flow. */
    public Clipboard clipboardContents() { return clipboard; }

    /** Snapshot the current selection into the clipboard. */
    public void copySelection() {
        if (selectedComponents.isEmpty()) return;
        var doc = doc();
        var ids = new java.util.HashSet<>(selectedComponents);
        var components = new ArrayList<CircuitComponent>();
        var positions = new ArrayList<ComponentPosition>();
        for (var id : selectedComponents) {
            var c = doc.component(id).orElse(null);
            if (c == null) continue;
            components.add(c);
            doc.layout().positionOf(id).ifPresent(positions::add);
        }
        var internalWires = new ArrayList<Wire>();
        for (var w : doc.wires()) {
            if (ids.contains(w.from().componentId()) && ids.contains(w.to().componentId())) {
                internalWires.add(w);
            }
        }
        clipboard = new Clipboard(List.copyOf(components), List.copyOf(positions), List.copyOf(internalWires));
    }

    /** Copy the selection to the clipboard, then delete it. */
    public void cutSelection() {
        if (selectedComponents.isEmpty()) return;
        copySelection();
        deleteSelection();
    }

    /**
     * Paste the clipboard at {@code (targetX, targetY)}, using the
     * clipboard's top-leftmost component as the anchor. Shortcut wrapper
     * around {@link #insertCluster} for callers that don't need rotation
     * or mirror. Fresh ids are assigned and names are de-duplicated
     * against the current document. The pasted components become the new
     * selection.
     */
    public void paste(double targetX, double targetY) {
        if (clipboard.isEmpty()) return;
        double anchorX = Double.POSITIVE_INFINITY;
        double anchorY = Double.POSITIVE_INFINITY;
        for (var p : clipboard.positions()) {
            if (p.x() < anchorX) anchorX = p.x();
            if (p.y() < anchorY) anchorY = p.y();
        }
        if (!Double.isFinite(anchorX)) { anchorX = targetX; anchorY = targetY; }
        var rel = new ArrayList<ComponentPosition>();
        for (var p : clipboard.positions()) {
            rel.add(new ComponentPosition(p.id(),
                p.x() - anchorX, p.y() - anchorY, p.rotationQuarters(), p.mirrored()));
        }
        insertCluster(clipboard.components(), rel, clipboard.internalWires(),
            targetX, targetY, 0, false);
    }

    /**
     * Insert a cluster of components at {@code (anchorX, anchorY)}, with each
     * component offset by its entry in {@code relativePositions} (rotated /
     * mirrored by the cluster's pending {@code rotationQuarters} /
     * {@code mirrored}). Wires in {@code internalWires} are recreated with
     * fresh ids so internal sub-circuit wiring survives the paste.
     *
     * <p>The pasted components become the new selection so the user can
     * immediately drag, rotate, or delete them as a group.
     */
    public void insertCluster(List<CircuitComponent> components,
                              List<ComponentPosition> relativePositions,
                              List<Wire> internalWires,
                              double anchorX, double anchorY,
                              int rotationQuarters, boolean mirrored) {
        if (components.isEmpty()) return;

        var existingNames = new java.util.HashSet<String>();
        var existingIds = new java.util.HashSet<ComponentId>();
        for (var c : doc().components()) {
            existingNames.add(c.name());
            existingIds.add(c.id());
        }
        var oldToNew = new java.util.HashMap<ComponentId, ComponentId>();

        var doc = doc();
        for (var original : components) {
            // Palette placement hands us fresh UUID-suffixed ids that can't
            // collide; paste hands us ids that are already in the doc. Only
            // regenerate on collision so single-component palette flow keeps
            // its original id.
            ComponentId newId = existingIds.contains(original.id())
                ? new ComponentId(original.id().value() + "-paste-" + UUID.randomUUID())
                : original.id();
            existingIds.add(newId);
            String newName = existingNames.contains(original.name())
                ? uniqueName(existingNames, original.name())
                : original.name();
            existingNames.add(newName);
            var clone = original.withName(newName).withId(newId);
            oldToNew.put(original.id(), newId);
            doc = doc.addComponent(clone, null);
        }
        var layout = doc.layout();
        for (var p : relativePositions) {
            var newId = oldToNew.get(p.id());
            if (newId == null) continue;
            double[] off = rotateOffset(p.x(), p.y(), rotationQuarters, mirrored);
            int finalRot = (p.rotationQuarters() + rotationQuarters) & 3;
            boolean finalMirror = p.mirrored() ^ mirrored;
            layout = layout.with(new ComponentPosition(newId,
                anchorX + off[0], anchorY + off[1], finalRot, finalMirror));
        }
        doc = doc.withLayout(layout);
        for (var w : internalWires) {
            var newFrom = oldToNew.get(w.from().componentId());
            var newTo = oldToNew.get(w.to().componentId());
            if (newFrom == null || newTo == null) continue;
            doc = doc.addWire(new Wire("wire-" + UUID.randomUUID(),
                new ComponentTerminal(newFrom, w.from().port()),
                new ComponentTerminal(newTo, w.to().port())));
        }

        replaceDocument(doc);
        selectedComponents.clear();
        selectedComponents.addAll(oldToNew.values());
    }

    private static double[] rotateOffset(double x, double y, int quarters, boolean mirror) {
        int q = ((quarters % 4) + 4) % 4;
        double rx = x, ry = y;
        for (int i = 0; i < q; i++) {
            double nx = -ry;
            double ny = rx;
            rx = nx; ry = ny;
        }
        if (mirror) rx = -rx;
        return new double[]{rx, ry};
    }

    private static CircuitComponent withId(CircuitComponent source, ComponentId newId) {
        return source.withId(newId);
    }

    private static String uniqueName(java.util.Set<String> existing, String base) {
        if (!existing.contains(base)) return base;
        int i = 2;
        while (existing.contains(base + " " + i)) i++;
        return base + " " + i;
    }
}
