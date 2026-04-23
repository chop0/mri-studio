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

    private static CircuitComponent withId(CircuitComponent source, ComponentId newId) {
        return switch (source) {
            case CircuitComponent.VoltageSource v -> new CircuitComponent.VoltageSource(
                newId, v.name(), v.kind(), v.carrierHz(),
                v.minAmplitude(), v.maxAmplitude(), v.outputImpedanceOhms());
            case CircuitComponent.SwitchComponent s -> new CircuitComponent.SwitchComponent(
                newId, s.name(), s.closedOhms(), s.openOhms(), s.thresholdVolts(), s.invertCtl());
            case CircuitComponent.Multiplexer m -> new CircuitComponent.Multiplexer(
                newId, m.name(), m.closedOhms(), m.openOhms(), m.thresholdVolts());
            case CircuitComponent.Coil c -> new CircuitComponent.Coil(
                newId, c.name(), c.eigenfieldId(), c.selfInductanceHenry(), c.seriesResistanceOhms());
            case CircuitComponent.Probe p -> new CircuitComponent.Probe(
                newId, p.name(), p.gain(), p.carrierHz(), p.demodPhaseDeg(), p.loadImpedanceOhms());
            case CircuitComponent.Resistor r -> new CircuitComponent.Resistor(newId, r.name(), r.resistanceOhms());
            case CircuitComponent.Capacitor c -> new CircuitComponent.Capacitor(newId, c.name(), c.capacitanceFarads());
            case CircuitComponent.Inductor l -> new CircuitComponent.Inductor(newId, l.name(), l.inductanceHenry());
            case CircuitComponent.ShuntResistor r -> new CircuitComponent.ShuntResistor(newId, r.name(), r.resistanceOhms());
            case CircuitComponent.ShuntCapacitor c -> new CircuitComponent.ShuntCapacitor(newId, c.name(), c.capacitanceFarads());
            case CircuitComponent.ShuntInductor l -> new CircuitComponent.ShuntInductor(newId, l.name(), l.inductanceHenry());
            case CircuitComponent.IdealTransformer t -> new CircuitComponent.IdealTransformer(newId, t.name(), t.turnsRatio());
        };
    }

    private static String uniqueName(java.util.Set<String> existing, String base) {
        if (!existing.contains(base)) return base;
        int i = 2;
        while (existing.contains(base + " " + i)) i++;
        return base + " " + i;
    }
}
