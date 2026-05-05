package ax.xz.mri.ui.workbench.pane.schematic;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentPosition;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.circuit.Wire;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.state.Autosaver;
import ax.xz.mri.state.DocumentEditor;
import ax.xz.mri.state.Mutation;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.state.ProjectStateIO;
import ax.xz.mri.state.RecordSurgery;
import ax.xz.mri.state.Scope;
import ax.xz.mri.state.Transaction;
import ax.xz.mri.state.UnifiedStateManager;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleIntegerProperty;
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
 * Per-circuit editor view-model.
 *
 * <p>Holds <em>session-only</em> state (selection, highlight overlay, status
 * banner). The underlying {@link CircuitDocument} is read live from the
 * {@link UnifiedStateManager} via a {@link DocumentEditor}; mutations dispatch
 * through the manager so they participate in the global undo log and autosave.
 */
public final class CircuitEditSession {

    private final DocumentEditor<CircuitDocument> editor;
    private final UnifiedStateManager state;
    private final ProjectNodeId circuitId;

    /** Live view of the circuit document — mirrors {@code state.current().circuit(id)}. */
    private final ReadOnlyObjectWrapper<CircuitDocument> current = new ReadOnlyObjectWrapper<>();
    /** Bumped on every {@code current} change; canvas redraws listen on this. */
    public final IntegerProperty revision = new SimpleIntegerProperty(0);

    public final ObservableSet<ComponentId> selectedComponents = FXCollections.observableSet(new LinkedHashSet<>());
    public final ObservableSet<String> selectedWires = FXCollections.observableSet(new LinkedHashSet<>());
    public final ObservableSet<ComponentId> highlightedComponents = FXCollections.observableSet(new LinkedHashSet<>());
    public final ObservableSet<String> highlightedWires = FXCollections.observableSet(new LinkedHashSet<>());
    public final StringProperty statusMessage = new SimpleStringProperty("");

    private Clipboard clipboard = new Clipboard(List.of(), List.of(), List.of());

    /**
     * Active drag/batch transaction. While non-null, every {@link #apply}
     * routes through it instead of dispatching immediately, so a whole
     * drag (or paste, or rotate) lands as a single undo entry.
     */
    private Transaction activeTx;

    /** Production constructor — wired into the project's state manager. */
    public CircuitEditSession(UnifiedStateManager state, ProjectNodeId circuitId) {
        this.state = state;
        this.circuitId = circuitId;
        this.editor = new DocumentEditor<>(state,
            Scope.indexed(Scope.root(), "circuits", circuitId),
            "schematic-editor",
            CircuitDocument.class);
        current.set(editor.value());
        editor.valueProperty().addListener((obs, o, n) -> {
            current.set(n);
            revision.set(revision.get() + 1);
            // Selection may reference deleted ids — keep it consistent.
            if (n != null) {
                var liveComponents = new java.util.HashSet<ComponentId>();
                for (var c : n.components()) liveComponents.add(c.id());
                selectedComponents.removeIf(id -> !liveComponents.contains(id));
                var liveWires = new java.util.HashSet<String>();
                for (var w : n.wires()) liveWires.add(w.id());
                selectedWires.removeIf(id -> !liveWires.contains(id));
            }
        });
    }

    /**
     * Test helper — builds a standalone state manager seeded with {@code doc}
     * and exposes a session over it. Used by editor tests that exercise the
     * helper methods (deleteSelection / addComponent / paste / …) without
     * spinning up a full project session.
     */
    public static CircuitEditSession standalone(CircuitDocument doc) {
        var surgery = new RecordSurgery();
        var io = new ProjectStateIO();
        var saver = new Autosaver(io::write, null);
        var manager = new UnifiedStateManager(
            ProjectState.empty().withCircuit(doc), surgery, saver, null);
        return new CircuitEditSession(manager, doc.id());
    }

    /** The state manager this session writes through. */
    public UnifiedStateManager state() { return state; }

    /** Current circuit document (snapshot of the live state). */
    public CircuitDocument doc() { return current.get(); }

    /** Live read-only view of the document. UI listeners bind to this. */
    public ReadOnlyObjectProperty<CircuitDocument> current() { return current.getReadOnlyProperty(); }

    public ReadOnlyBooleanProperty canUndoProperty() { return editor.canUndoProperty(); }
    public ReadOnlyBooleanProperty canRedoProperty() { return editor.canRedoProperty(); }

    public boolean canUndo() { return editor.canUndoProperty().get(); }
    public boolean canRedo() { return editor.canRedoProperty().get(); }

    public void apply(UnaryOperator<CircuitDocument> delta) {
        if (activeTx != null) activeTx.apply(delta);
        else editor.apply(delta, "Edit circuit");
    }

    /**
     * Replace the whole document atomically as one user edit. Used by batch
     * operations (delete, paste, duplicate) that rebuild the doc in one step.
     */
    public void replaceDocument(CircuitDocument next) {
        apply(prev -> next);
    }

    /**
     * Begin a coalescing transaction. While the transaction is open, all
     * {@link #apply} calls accumulate into a single undo entry. Call
     * {@link #endTransaction()} on drag-end / batch-commit to flush.
     *
     * <p>Editors should use try/finally to ensure {@link #endTransaction()}
     * runs even if the drag handler throws.
     */
    public void beginTransaction(String label) {
        if (activeTx != null) {
            throw new IllegalStateException("Circuit transaction already active");
        }
        activeTx = editor.beginTransaction(label);
    }

    /** Commit any active transaction. No-op if none is open. */
    public void endTransaction() {
        if (activeTx == null) return;
        var tx = activeTx;
        activeTx = null;
        tx.commit();
    }

    public void undo() { editor.undo(); }
    public void redo() { editor.redo(); }

    /* ── Selection ───────────────────────────────────────────────────────── */

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

    public void setHighlight(java.util.Collection<ComponentId> components,
                             java.util.Collection<String> wires) {
        highlightedComponents.clear();
        highlightedWires.clear();
        if (components != null) highlightedComponents.addAll(components);
        if (wires != null) highlightedWires.addAll(wires);
    }

    public void clearHighlight() {
        highlightedComponents.clear();
        highlightedWires.clear();
    }

    /* ── Component / wire helpers ───────────────────────────────────────── */

    public void deleteSelection() {
        if (selectedComponents.isEmpty() && selectedWires.isEmpty()) return;
        var toRemoveComponents = new ArrayList<>(selectedComponents);
        var toRemoveWires = new ArrayList<>(selectedWires);
        editor.apply(d -> {
            var next = d;
            for (var id : toRemoveComponents) next = next.removeComponent(id);
            for (var id : toRemoveWires) next = next.removeWire(id);
            return next;
        }, "Delete selection");
    }

    public void addComponent(CircuitComponent component, ComponentPosition position) {
        var existingNames = new java.util.HashSet<String>();
        for (var c : doc().components()) existingNames.add(c.name());
        var withUniqueName = existingNames.contains(component.name())
            ? component.withName(uniqueName(existingNames, component.name()))
            : component;
        var finalPosition = (position == null || !position.id().equals(withUniqueName.id()))
            ? (position == null ? null
                : new ComponentPosition(withUniqueName.id(), position.x(), position.y(), position.rotationQuarters()))
            : position;
        editor.apply(d -> d.addComponent(withUniqueName, finalPosition), "Add component");
        selectOnly(withUniqueName.id());
    }

    public void replaceComponent(CircuitComponent updated) {
        editor.apply(d -> d.replaceComponent(updated), "Edit " + updated.name());
    }

    public void moveComponent(ComponentId id, double newX, double newY) {
        editor.apply(d -> {
            var existing = d.layout().positionOf(id).orElse(null);
            int rot = existing == null ? 0 : existing.rotationQuarters();
            boolean mirrored = existing != null && existing.mirrored();
            return d.withLayout(d.layout().with(new ComponentPosition(id, newX, newY, rot, mirrored)));
        }, "Move component");
    }

    public void rotateSelection() {
        if (selectedComponents.isEmpty()) return;
        editor.apply(d -> {
            var layout = d.layout();
            for (var id : selectedComponents) {
                var pos = layout.positionOf(id).orElse(null);
                if (pos != null) layout = layout.with(pos.withRotationQuarters(pos.rotationQuarters() + 1));
            }
            return d.withLayout(layout);
        }, "Rotate selection");
    }

    public void mirrorSelection() {
        if (selectedComponents.isEmpty()) return;
        editor.apply(d -> {
            var layout = d.layout();
            for (var id : selectedComponents) {
                var pos = layout.positionOf(id).orElse(null);
                if (pos != null) layout = layout.with(pos.withMirrored(!pos.mirrored()));
            }
            return d.withLayout(layout);
        }, "Mirror selection");
    }

    public void addWire(ComponentTerminal from, ComponentTerminal to) {
        var wire = new Wire("wire-" + UUID.randomUUID(), from, to);
        editor.apply(d -> d.addWire(wire), "Add wire");
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

    public void duplicateSelection(double dx, double dy) {
        if (selectedComponents.isEmpty()) return;
        var idsToDuplicate = new java.util.HashSet<>(selectedComponents);
        var oldToNew = new java.util.HashMap<ComponentId, ComponentId>();
        var newSelection = new java.util.LinkedHashSet<ComponentId>();
        editor.apply(d -> {
            var existingNames = new java.util.HashSet<String>();
            for (var c : d.components()) existingNames.add(c.name());
            var additions = new ArrayList<CircuitComponent>();
            var positions = new ArrayList<ComponentPosition>();
            oldToNew.clear();
            for (var id : idsToDuplicate) {
                var original = d.component(id).orElse(null);
                if (original == null) continue;
                var cloneId = new ComponentId(original.id().value() + "-copy-" + UUID.randomUUID());
                String cloneName = uniqueName(existingNames, original.name());
                existingNames.add(cloneName);
                var clone = original.withName(cloneName).withId(cloneId);
                oldToNew.put(original.id(), cloneId);
                additions.add(clone);
                var pos = d.layout().positionOf(original.id()).orElse(null);
                if (pos != null) {
                    positions.add(new ComponentPosition(cloneId, pos.x() + dx, pos.y() + dy, pos.rotationQuarters()));
                }
            }
            var next = d;
            for (var addition : additions) next = next.addComponent(addition, null);
            var layout = next.layout();
            for (var p : positions) layout = layout.with(p);
            next = next.withLayout(layout);
            for (var w : d.wires()) {
                var fromClone = oldToNew.get(w.from().componentId());
                var toClone = oldToNew.get(w.to().componentId());
                if (fromClone != null && toClone != null) {
                    next = next.addWire(new Wire("wire-" + UUID.randomUUID(),
                        new ComponentTerminal(fromClone, w.from().port()),
                        new ComponentTerminal(toClone, w.to().port())));
                }
            }
            newSelection.addAll(oldToNew.values());
            return next;
        }, "Duplicate selection");
        selectedComponents.clear();
        selectedComponents.addAll(newSelection);
    }

    /* ── Clipboard ──────────────────────────────────────────────────────── */

    /**
     * In-memory snapshot of a selection the user cut or copied. Cross-boundary
     * wires (from a clipboard component to one not in the selection) are
     * dropped — there's no target on the paste side.
     */
    public record Clipboard(
        List<CircuitComponent> components,
        List<ComponentPosition> positions,
        List<Wire> internalWires
    ) {
        public boolean isEmpty() { return components.isEmpty(); }
    }

    public boolean clipboardIsEmpty() { return clipboard.isEmpty(); }

    public Clipboard clipboardContents() { return clipboard; }

    public void copySelection() {
        if (selectedComponents.isEmpty()) return;
        var d = doc();
        var ids = new java.util.HashSet<>(selectedComponents);
        var components = new ArrayList<CircuitComponent>();
        var positions = new ArrayList<ComponentPosition>();
        for (var id : selectedComponents) {
            var c = d.component(id).orElse(null);
            if (c == null) continue;
            components.add(c);
            d.layout().positionOf(id).ifPresent(positions::add);
        }
        var internalWires = new ArrayList<Wire>();
        for (var w : d.wires()) {
            if (ids.contains(w.from().componentId()) && ids.contains(w.to().componentId())) {
                internalWires.add(w);
            }
        }
        clipboard = new Clipboard(List.copyOf(components), List.copyOf(positions), List.copyOf(internalWires));
    }

    public void cutSelection() {
        if (selectedComponents.isEmpty()) return;
        copySelection();
        deleteSelection();
    }

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

    public void insertCluster(List<CircuitComponent> components,
                              List<ComponentPosition> relativePositions,
                              List<Wire> internalWires,
                              double anchorX, double anchorY,
                              int rotationQuarters, boolean mirrored) {
        if (components.isEmpty()) return;
        var oldToNew = new java.util.HashMap<ComponentId, ComponentId>();
        var newSelection = new java.util.LinkedHashSet<ComponentId>();
        editor.apply(d -> {
            var existingNames = new java.util.HashSet<String>();
            var existingIds = new java.util.HashSet<ComponentId>();
            for (var c : d.components()) {
                existingNames.add(c.name());
                existingIds.add(c.id());
            }
            oldToNew.clear();
            var next = d;
            for (var original : components) {
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
                next = next.addComponent(clone, null);
            }
            var layout = next.layout();
            for (var p : relativePositions) {
                var newId = oldToNew.get(p.id());
                if (newId == null) continue;
                double[] off = rotateOffset(p.x(), p.y(), rotationQuarters, mirrored);
                int finalRot = (p.rotationQuarters() + rotationQuarters) & 3;
                boolean finalMirror = p.mirrored() ^ mirrored;
                layout = layout.with(new ComponentPosition(newId,
                    anchorX + off[0], anchorY + off[1], finalRot, finalMirror));
            }
            next = next.withLayout(layout);
            for (var w : internalWires) {
                var newFrom = oldToNew.get(w.from().componentId());
                var newTo = oldToNew.get(w.to().componentId());
                if (newFrom == null || newTo == null) continue;
                next = next.addWire(new Wire("wire-" + UUID.randomUUID(),
                    new ComponentTerminal(newFrom, w.from().port()),
                    new ComponentTerminal(newTo, w.to().port())));
            }
            newSelection.addAll(oldToNew.values());
            return next;
        }, "Paste");
        selectedComponents.clear();
        selectedComponents.addAll(newSelection);
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

    private static String uniqueName(java.util.Set<String> existing, String base) {
        if (!existing.contains(base)) return base;
        int i = 2;
        while (existing.contains(base + " " + i)) i++;
        return base + " " + i;
    }
}
