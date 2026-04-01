package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.Segment;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.SequenceDocument;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Mutable editing state for one sequence, with full undo/redo.
 *
 * <p>All mutations go through named methods that automatically push an undo snapshot
 * before applying the change. The {@link #revision} counter is bumped on every edit
 * and drives canvas redraws.
 */
public final class SequenceEditSession {
    private static final int MAX_UNDO = 100;

    public final ObjectProperty<SequenceDocument> originalDocument = new SimpleObjectProperty<>();
    public final ObservableList<Segment> segments = FXCollections.observableArrayList();
    public final ObservableList<PulseSegment> pulseSegments = FXCollections.observableArrayList();
    public final IntegerProperty selectedSegmentIndex = new SimpleIntegerProperty(-1);
    public final IntegerProperty revision = new SimpleIntegerProperty(0);

    private final BooleanProperty canUndo = new SimpleBooleanProperty(false);
    private final BooleanProperty canRedo = new SimpleBooleanProperty(false);
    private final Deque<EditSnapshot> undoStack = new ArrayDeque<>();
    private final Deque<EditSnapshot> redoStack = new ArrayDeque<>();

    public ReadOnlyBooleanProperty canUndoProperty() { return canUndo; }
    public ReadOnlyBooleanProperty canRedoProperty() { return canRedo; }

    /** Load a sequence document for editing. Clears undo history. */
    public void open(SequenceDocument document) {
        originalDocument.set(document);
        segments.setAll(document.segments());
        pulseSegments.setAll(document.pulse());
        selectedSegmentIndex.set(segments.isEmpty() ? -1 : 0);
        undoStack.clear();
        redoStack.clear();
        canUndo.set(false);
        canRedo.set(false);
        bumpRevision();
    }

    /** Build an immutable document from the current editing state. */
    public SequenceDocument toDocument() {
        var orig = originalDocument.get();
        return new SequenceDocument(
            orig.id(),
            orig.name(),
            List.copyOf(segments),
            List.copyOf(pulseSegments)
        );
    }

    public boolean isDirty() {
        var orig = originalDocument.get();
        if (orig == null) return false;
        return !segments.equals(orig.segments()) || !pulseSegments.equals(orig.pulse());
    }

    // --- Mutations (all push undo) ---

    public void setPulseStep(int segIndex, int stepIndex, PulseStep newStep) {
        pushUndo();
        var seg = pulseSegments.get(segIndex);
        var steps = new ArrayList<>(seg.steps());
        steps.set(stepIndex, newStep);
        pulseSegments.set(segIndex, new PulseSegment(steps));
        bumpRevision();
    }

    public void setPulseRange(int segIndex, int startStep, PulseStep[] newSteps) {
        pushUndo();
        var seg = pulseSegments.get(segIndex);
        var steps = new ArrayList<>(seg.steps());
        for (int i = 0; i < newSteps.length && (startStep + i) < steps.size(); i++) {
            steps.set(startStep + i, newSteps[i]);
        }
        pulseSegments.set(segIndex, new PulseSegment(steps));
        bumpRevision();
    }

    public void insertSegment(int index, Segment segment, PulseSegment pulseSegment) {
        pushUndo();
        segments.add(index, segment);
        pulseSegments.add(index, pulseSegment);
        selectedSegmentIndex.set(index);
        bumpRevision();
    }

    /** Insert a default empty segment after the current selection. */
    public void insertSegmentAfterSelection() {
        int insertAt = Math.max(0, selectedSegmentIndex.get() + 1);
        if (insertAt > segments.size()) insertAt = segments.size();
        // Default: 10μs time step, 10 free-precession steps, 20 RF steps
        var seg = new Segment(10e-6, 10, 20);
        var steps = new ArrayList<PulseStep>();
        for (int i = 0; i < seg.totalSteps(); i++) {
            steps.add(new PulseStep(0, 0, 0, 0, i >= seg.nFree() ? 1.0 : 0.0));
        }
        insertSegment(insertAt, seg, new PulseSegment(steps));
    }

    public void removeSegment(int index) {
        if (segments.size() <= 1) return; // don't allow empty sequence
        pushUndo();
        segments.remove(index);
        pulseSegments.remove(index);
        if (selectedSegmentIndex.get() >= segments.size()) {
            selectedSegmentIndex.set(segments.size() - 1);
        }
        bumpRevision();
    }

    public void removeSelectedSegment() {
        int sel = selectedSegmentIndex.get();
        if (sel >= 0 && sel < segments.size()) {
            removeSegment(sel);
        }
    }

    public void duplicateSegment(int index) {
        pushUndo();
        var seg = segments.get(index);
        var pulse = pulseSegments.get(index);
        // Deep copy the pulse steps
        var copiedSteps = pulse.steps().stream()
            .map(s -> new PulseStep(s.b1x(), s.b1y(), s.gx(), s.gz(), s.rfGate()))
            .toList();
        segments.add(index + 1, seg);
        pulseSegments.add(index + 1, new PulseSegment(copiedSteps));
        selectedSegmentIndex.set(index + 1);
        bumpRevision();
    }

    public void duplicateSelectedSegment() {
        int sel = selectedSegmentIndex.get();
        if (sel >= 0 && sel < segments.size()) {
            duplicateSegment(sel);
        }
    }

    // --- Undo/Redo ---

    public void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.push(captureSnapshot());
        applySnapshot(undoStack.pop());
        updateUndoRedoFlags();
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(captureSnapshot());
        applySnapshot(redoStack.pop());
        updateUndoRedoFlags();
    }

    private void pushUndo() {
        undoStack.push(captureSnapshot());
        if (undoStack.size() > MAX_UNDO) {
            // Remove oldest — ArrayDeque doesn't have removeLast for a stack, so we rebuild
            var temp = new ArrayDeque<EditSnapshot>(MAX_UNDO);
            int count = 0;
            for (var snapshot : undoStack) {
                if (count++ >= MAX_UNDO) break;
                temp.push(snapshot);
            }
            undoStack.clear();
            // Reverse temp back into undoStack
            var reversed = new ArrayDeque<EditSnapshot>(temp.size());
            while (!temp.isEmpty()) reversed.push(temp.pop());
            while (!reversed.isEmpty()) undoStack.push(reversed.pop());
        }
        redoStack.clear();
        updateUndoRedoFlags();
    }

    private EditSnapshot captureSnapshot() {
        return new EditSnapshot(
            List.copyOf(segments),
            List.copyOf(pulseSegments),
            selectedSegmentIndex.get()
        );
    }

    private void applySnapshot(EditSnapshot snapshot) {
        segments.setAll(snapshot.segments);
        pulseSegments.setAll(snapshot.pulseSegments);
        selectedSegmentIndex.set(snapshot.selectedSegmentIndex);
        bumpRevision();
    }

    private void updateUndoRedoFlags() {
        canUndo.set(!undoStack.isEmpty());
        canRedo.set(!redoStack.isEmpty());
    }

    private void bumpRevision() {
        revision.set(revision.get() + 1);
    }

    private record EditSnapshot(
        List<Segment> segments,
        List<PulseSegment> pulseSegments,
        int selectedSegmentIndex
    ) {
        EditSnapshot {
            segments = List.copyOf(segments);
            pulseSegments = List.copyOf(pulseSegments);
        }
    }
}
