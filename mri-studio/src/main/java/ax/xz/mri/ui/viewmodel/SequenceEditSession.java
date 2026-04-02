package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.sequence.ClipBaker;
import ax.xz.mri.model.sequence.ClipSequence;
import ax.xz.mri.model.sequence.ClipShape;
import ax.xz.mri.model.sequence.SignalChannel;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.project.SequenceDocument;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableSet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable editing state for one clip-based sequence, with full undo/redo
 * and multi-clip selection.
 *
 * <p>All mutations go through named methods that automatically push an undo snapshot
 * before applying the change. The {@link #revision} counter is bumped on every edit
 * and drives canvas redraws.
 */
public final class SequenceEditSession {
    private static final int MAX_UNDO = 100;
    private static final double DEFAULT_DT = 10.0;          // μs
    private static final double DEFAULT_DURATION = 3000.0;   // μs (3 ms)
    private static final double SNAP_THRESHOLD_FRACTION = 0.005; // 0.5% of visible span

    // --- Core state ---
    public final ObjectProperty<SequenceDocument> originalDocument = new SimpleObjectProperty<>();
    public final ObservableList<SignalClip> clips = FXCollections.observableArrayList();
    public final DoubleProperty dt = new SimpleDoubleProperty(DEFAULT_DT);
    public final DoubleProperty totalDuration = new SimpleDoubleProperty(DEFAULT_DURATION);
    public final IntegerProperty revision = new SimpleIntegerProperty(0);

    // --- Selection (multi-select) ---
    public final ObservableSet<String> selectedClipIds = FXCollections.observableSet(new LinkedHashSet<>());
    /** The most recently clicked clip — drives inspector display. */
    public final ObjectProperty<String> primarySelectedClipId = new SimpleObjectProperty<>(null);

    // --- Tracks ---
    public final ObservableList<EditorTrack> tracks = FXCollections.observableArrayList();

    // --- Viewport (editor's own zoom/pan, in μs) ---
    public final DoubleProperty viewStart = new SimpleDoubleProperty(0);
    public final DoubleProperty viewEnd = new SimpleDoubleProperty(DEFAULT_DURATION);

    // --- Snapping ---
    public final BooleanProperty snapEnabled = new SimpleBooleanProperty(true);
    public final DoubleProperty snapGridSize = new SimpleDoubleProperty(0);

    // --- Undo/Redo ---
    private final BooleanProperty canUndo = new SimpleBooleanProperty(false);
    private final BooleanProperty canRedo = new SimpleBooleanProperty(false);
    private final Deque<EditSnapshot> undoStack = new ArrayDeque<>();
    private final Deque<EditSnapshot> redoStack = new ArrayDeque<>();

    public ReadOnlyBooleanProperty canUndoProperty() { return canUndo; }
    public ReadOnlyBooleanProperty canRedoProperty() { return canRedo; }

    // ==================== Document loading ====================

    /** Load a sequence document for editing. Clears undo history. */
    public void open(SequenceDocument document) {
        originalDocument.set(document);

        ClipSequence clipSeq = document.clipSequence();
        if (clipSeq != null) {
            clips.setAll(clipSeq.clips());
            dt.set(clipSeq.dt());
            totalDuration.set(clipSeq.totalDuration());
        } else {
            var converted = ClipBaker.fromLegacy(document.segments(), document.pulse());
            clips.setAll(converted.clips());
            dt.set(converted.dt());
            totalDuration.set(converted.totalDuration());
        }

        selectedClipIds.clear();
        primarySelectedClipId.set(null);
        tracks.setAll(EditorTrack.defaultTracks());
        viewStart.set(0);
        viewEnd.set(totalDuration.get());
        undoStack.clear();
        redoStack.clear();
        canUndo.set(false);
        canRedo.set(false);
        bumpRevision();
    }

    /** Build an immutable document from the current editing state, baking clips to steps. */
    public SequenceDocument toDocument() {
        var orig = originalDocument.get();
        var clipSeq = new ClipSequence(dt.get(), totalDuration.get(), List.copyOf(clips));
        var baked = ClipBaker.bake(clipSeq);
        return new SequenceDocument(
            orig.id(), orig.name(),
            baked.segments(), baked.pulseSegments(),
            clipSeq
        );
    }

    public boolean isDirty() {
        var orig = originalDocument.get();
        if (orig == null) return false;
        if (orig.clipSequence() != null) {
            return !clips.equals(orig.clipSequence().clips())
                || dt.get() != orig.clipSequence().dt()
                || totalDuration.get() != orig.clipSequence().totalDuration();
        }
        return true;
    }

    // ==================== Selection ====================

    public boolean isSelected(String clipId) {
        return selectedClipIds.contains(clipId);
    }

    public List<SignalClip> selectedClips() {
        return clips.stream().filter(c -> selectedClipIds.contains(c.id())).toList();
    }

    public void selectOnly(String clipId) {
        selectedClipIds.clear();
        if (clipId != null) {
            selectedClipIds.add(clipId);
            primarySelectedClipId.set(clipId);
        } else {
            primarySelectedClipId.set(null);
        }
    }

    public void addToSelection(String clipId) {
        if (clipId != null) {
            selectedClipIds.add(clipId);
            primarySelectedClipId.set(clipId);
        }
    }

    public void toggleSelection(String clipId) {
        if (clipId == null) return;
        if (selectedClipIds.contains(clipId)) {
            selectedClipIds.remove(clipId);
            if (clipId.equals(primarySelectedClipId.get())) {
                primarySelectedClipId.set(selectedClipIds.isEmpty() ? null : selectedClipIds.iterator().next());
            }
        } else {
            selectedClipIds.add(clipId);
            primarySelectedClipId.set(clipId);
        }
    }

    public void clearSelection() {
        selectedClipIds.clear();
        primarySelectedClipId.set(null);
    }

    /** Select all clips overlapping a time×channel region (for rubber-band). */
    public void selectClipsInRegion(double timeStart, double timeEnd, Set<SignalChannel> channels, boolean addToExisting) {
        if (!addToExisting) selectedClipIds.clear();
        double tMin = Math.min(timeStart, timeEnd);
        double tMax = Math.max(timeStart, timeEnd);
        String first = null;
        for (var clip : clips) {
            if (channels.contains(clip.channel()) && clip.endTime() > tMin && clip.startTime() < tMax) {
                selectedClipIds.add(clip.id());
                if (first == null) first = clip.id();
            }
        }
        if (first != null) primarySelectedClipId.set(first);
    }

    // ==================== Clip queries ====================

    public SignalClip findClip(String clipId) {
        if (clipId == null) return null;
        for (var clip : clips) {
            if (clip.id().equals(clipId)) return clip;
        }
        return null;
    }

    private int indexOfClip(String clipId) {
        for (int i = 0; i < clips.size(); i++) {
            if (clips.get(i).id().equals(clipId)) return i;
        }
        return -1;
    }

    /** Get the primary selected clip, or null. */
    public SignalClip primarySelectedClip() {
        return findClip(primarySelectedClipId.get());
    }

    /** Get all clips on a given channel, sorted by start time. */
    public List<SignalClip> clipsOnChannel(SignalChannel channel) {
        return clips.stream()
            .filter(c -> c.channel() == channel)
            .sorted((a, b) -> Double.compare(a.startTime(), b.startTime()))
            .toList();
    }

    // ==================== Track management ====================

    public void addTrack(SignalChannel channel) {
        pushUndo();
        int count = (int) tracks.stream().filter(t -> t.channel() == channel).count();
        tracks.add(new EditorTrack(
            UUID.randomUUID().toString(), channel,
            channel.label() + " " + (count + 1), false
        ));
        bumpRevision();
    }

    public void removeTrack(String trackId) {
        var track = tracks.stream().filter(t -> t.id().equals(trackId)).findFirst().orElse(null);
        if (track == null) return;
        // Don't remove the last track for a channel
        long channelCount = tracks.stream().filter(t -> t.channel() == track.channel()).count();
        if (channelCount <= 1) return;
        pushUndo();
        tracks.removeIf(t -> t.id().equals(trackId));
        bumpRevision();
    }

    public void reorderTrack(int fromIndex, int toIndex) {
        if (fromIndex < 0 || toIndex < 0 || fromIndex >= tracks.size() || toIndex >= tracks.size()) return;
        if (fromIndex == toIndex) return;
        pushUndo();
        var moved = tracks.remove(fromIndex);
        tracks.add(toIndex, moved);
        bumpRevision();
    }

    public void setTrackCollapsed(String trackId, boolean collapsed) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).id().equals(trackId)) {
                tracks.set(i, tracks.get(i).withCollapsed(collapsed));
                bumpRevision();
                return;
            }
        }
    }

    // ==================== Mutations (all push undo) ====================

    public void addClip(SignalClip clip) {
        pushUndo();
        clips.add(clip);
        selectOnly(clip.id());
        bumpRevision();
    }

    public void removeClip(String clipId) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        pushUndo();
        clips.remove(idx);
        selectedClipIds.remove(clipId);
        if (clipId.equals(primarySelectedClipId.get())) {
            primarySelectedClipId.set(selectedClipIds.isEmpty() ? null : selectedClipIds.iterator().next());
        }
        bumpRevision();
    }

    /** Delete all selected clips. */
    public void deleteSelectedClips() {
        if (selectedClipIds.isEmpty()) return;
        pushUndo();
        var toRemove = Set.copyOf(selectedClipIds);
        clips.removeIf(c -> toRemove.contains(c.id()));
        selectedClipIds.clear();
        primarySelectedClipId.set(null);
        bumpRevision();
    }

    public void moveClip(String clipId, double newStartTime) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        newStartTime = Math.max(0, newStartTime);
        pushUndo();
        clips.set(idx, clips.get(idx).withStartTime(newStartTime));
        bumpRevision();
    }

    /** Move all selected clips by a time delta. Single undo snapshot. */
    public void moveSelectedClips(double deltaMicros) {
        if (selectedClipIds.isEmpty()) return;
        pushUndo();
        for (int i = 0; i < clips.size(); i++) {
            var clip = clips.get(i);
            if (selectedClipIds.contains(clip.id())) {
                double newStart = Math.max(0, clip.startTime() + deltaMicros);
                clips.set(i, clip.withStartTime(newStart));
            }
        }
        bumpRevision();
    }

    /**
     * Resize clip from the right edge. If the user extends past the current media
     * extent, the mediaDuration grows to accommodate (infinite extension).
     */
    public void resizeClip(String clipId, double newDuration) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        var clip = clips.get(idx);
        newDuration = Math.max(dt.get(), newDuration);
        double neededMedia = clip.mediaOffset() + newDuration;
        pushUndo();
        if (neededMedia > clip.mediaDuration()) {
            // Grow the media extent to fit
            clips.set(idx, clip.withDuration(newDuration).withMediaDuration(neededMedia));
        } else {
            clips.set(idx, clip.withDuration(newDuration));
        }
        bumpRevision();
    }

    /**
     * Premiere-style left-edge trim: adjusts startTime and mediaOffset together,
     * keeping the waveform in place. If extended past the media start, the media
     * extent grows (mediaOffset goes to 0, mediaDuration increases).
     */
    public void resizeClipLeft(String clipId, double newStartTime) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        var clip = clips.get(idx);
        newStartTime = Math.max(0, Math.min(newStartTime, clip.endTime() - dt.get()));
        double delta = newStartTime - clip.startTime();
        double newDuration = clip.endTime() - newStartTime;
        double newMediaOffset = clip.mediaOffset() + delta;
        double newMediaDuration = clip.mediaDuration();

        if (newMediaOffset < 0) {
            // Extending past media start — grow media extent
            newMediaDuration += -newMediaOffset;
            newMediaOffset = 0;
        }

        pushUndo();
        clips.set(idx, new SignalClip(
            clip.id(), clip.channel(), clip.shape(),
            newStartTime, newDuration, clip.amplitude(),
            newMediaOffset, newMediaDuration,
            clip.params(), clip.splinePoints()
        ));
        bumpRevision();
    }

    public void setClipAmplitude(String clipId, double amplitude) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        pushUndo();
        clips.set(idx, clips.get(idx).withAmplitude(amplitude));
        bumpRevision();
    }

    public void setClipParam(String clipId, String paramName, double value) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        pushUndo();
        var clip = clips.get(idx);
        var newParams = new java.util.HashMap<>(clip.params());
        newParams.put(paramName, value);
        clips.set(idx, clip.withParams(newParams));
        bumpRevision();
    }

    public void setClipShape(String clipId, ClipShape newShape) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        pushUndo();
        clips.set(idx, clips.get(idx).withShape(newShape));
        bumpRevision();
    }

    public void changeClipChannel(String clipId, SignalChannel newChannel) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        pushUndo();
        clips.set(idx, clips.get(idx).withChannel(newChannel));
        bumpRevision();
    }

    /** Duplicate all selected clips, offset slightly. */
    public void duplicateSelectedClips() {
        var sel = selectedClips();
        if (sel.isEmpty()) return;
        pushUndo();
        var newIds = new LinkedHashSet<String>();
        for (var clip : sel) {
            var dupe = clip.withNewId().withStartTime(clip.startTime() + clip.duration() * 0.1);
            clips.add(dupe);
            newIds.add(dupe.id());
        }
        selectedClipIds.clear();
        selectedClipIds.addAll(newIds);
        primarySelectedClipId.set(newIds.iterator().next());
        bumpRevision();
    }

    public void duplicateClip(String clipId) {
        var clip = findClip(clipId);
        if (clip == null) return;
        pushUndo();
        var dupe = clip.withNewId().withStartTime(clip.startTime() + clip.duration() * 0.1);
        clips.add(dupe);
        selectOnly(dupe.id());
        bumpRevision();
    }

    public void setTotalDuration(double duration) {
        if (duration <= 0) return;
        pushUndo();
        totalDuration.set(duration);
        if (viewEnd.get() > duration) viewEnd.set(duration);
        bumpRevision();
    }

    /**
     * Re-centre a clip's media extent around its current visible portion,
     * giving equal headroom on both sides for future trimming.
     */
    public void recentreClip(String clipId) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        var clip = clips.get(idx);
        double newMediaDuration = clip.duration() * 4.0;
        double newMediaOffset = clip.duration() * 1.5;
        pushUndo();
        clips.set(idx, new SignalClip(
            clip.id(), clip.channel(), clip.shape(),
            clip.startTime(), clip.duration(), clip.amplitude(),
            newMediaOffset, newMediaDuration,
            clip.params(), clip.splinePoints()
        ));
        bumpRevision();
    }

    public void replaceClip(String clipId, SignalClip replacement) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        pushUndo();
        clips.set(idx, replacement);
        bumpRevision();
    }

    // ==================== Spline points ====================

    public void updateSplinePoint(String clipId, int pointIndex, SignalClip.SplinePoint newPoint) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        var clip = clips.get(idx);
        if (pointIndex < 0 || pointIndex >= clip.splinePoints().size()) return;
        pushUndo();
        var points = new ArrayList<>(clip.splinePoints());
        points.set(pointIndex, newPoint);
        clips.set(idx, clip.withSplinePoints(points));
        bumpRevision();
    }

    public void addSplinePoint(String clipId, SignalClip.SplinePoint point) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        pushUndo();
        var clip = clips.get(idx);
        var points = new ArrayList<>(clip.splinePoints());
        int insertAt = 0;
        while (insertAt < points.size() && points.get(insertAt).t() < point.t()) insertAt++;
        points.add(insertAt, point);
        clips.set(idx, clip.withSplinePoints(points));
        bumpRevision();
    }

    public void removeSplinePoint(String clipId, int pointIndex) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        var clip = clips.get(idx);
        if (pointIndex < 0 || pointIndex >= clip.splinePoints().size()) return;
        if (clip.splinePoints().size() <= 2) return;
        pushUndo();
        var points = new ArrayList<>(clip.splinePoints());
        points.remove(pointIndex);
        clips.set(idx, clip.withSplinePoints(points));
        bumpRevision();
    }

    // ==================== Split ====================

    /**
     * Split a clip at the given time into two clips.
     * For sinc clips, preserves bandwidth and adjusts centerOffset so the waveform doesn't shift.
     * For spline clips, remaps control points proportionally.
     */
    public void splitClip(String clipId, double splitTime) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        var clip = clips.get(idx);
        if (splitTime <= clip.startTime() || splitTime >= clip.endTime()) return;

        pushUndo();
        double leftDuration = splitTime - clip.startTime();
        double rightDuration = clip.endTime() - splitTime;

        // Left half
        var leftParams = new java.util.HashMap<>(clip.params());
        var rightParams = new java.util.HashMap<>(clip.params());

        if (clip.shape() == ClipShape.SINC) {
            // Preserve the sinc's true centre by adjusting centerOffset
            double originalCenter = clip.duration() / 2.0 + clip.param("centerOffset", 0);
            // Left clip: center is at originalCenter relative to original start
            // Left clip's own center would be at leftDuration/2, so offset = originalCenter - leftDuration/2
            leftParams.put("centerOffset", originalCenter - leftDuration / 2.0);
            // Right clip: the sinc center in right-clip-local coords
            double rightLocalCenter = originalCenter - leftDuration;
            rightParams.put("centerOffset", rightLocalCenter - rightDuration / 2.0);
        }

        List<SignalClip.SplinePoint> leftPoints = List.of();
        List<SignalClip.SplinePoint> rightPoints = List.of();
        if (clip.shape() == ClipShape.SPLINE && !clip.splinePoints().isEmpty()) {
            double splitU = leftDuration / clip.duration();
            var lp = new ArrayList<SignalClip.SplinePoint>();
            var rp = new ArrayList<SignalClip.SplinePoint>();
            for (var pt : clip.splinePoints()) {
                if (pt.t() <= splitU) {
                    lp.add(new SignalClip.SplinePoint(pt.t() / splitU, pt.value()));
                }
                if (pt.t() >= splitU) {
                    rp.add(new SignalClip.SplinePoint((pt.t() - splitU) / (1 - splitU), pt.value()));
                }
            }
            if (lp.isEmpty() || lp.getLast().t() < 1.0) lp.add(new SignalClip.SplinePoint(1.0, 0));
            if (rp.isEmpty() || rp.getFirst().t() > 0.0) rp.addFirst(new SignalClip.SplinePoint(0.0, 0));
            leftPoints = lp;
            rightPoints = rp;
        }

        var leftClip = new SignalClip(
            UUID.randomUUID().toString(), clip.channel(), clip.shape(),
            clip.startTime(), leftDuration, clip.amplitude(),
            leftParams, leftPoints
        );
        var rightClip = new SignalClip(
            UUID.randomUUID().toString(), clip.channel(), clip.shape(),
            splitTime, rightDuration, clip.amplitude(),
            rightParams, rightPoints
        );

        clips.set(idx, leftClip);
        clips.add(idx + 1, rightClip);
        selectedClipIds.clear();
        selectedClipIds.add(leftClip.id());
        selectedClipIds.add(rightClip.id());
        primarySelectedClipId.set(leftClip.id());
        bumpRevision();
    }

    // ==================== Snapping ====================

    /**
     * Snap a time value to nearby clip edges or grid points.
     * Returns the snapped time, or the original if nothing is close.
     */
    public double snapTime(double rawTime) {
        if (!snapEnabled.get()) return rawTime;
        double viewSpan = viewEnd.get() - viewStart.get();
        double threshold = viewSpan * SNAP_THRESHOLD_FRACTION;

        // Snap to clip edges (across all clips)
        double bestSnap = rawTime;
        double bestDist = threshold;
        for (var clip : clips) {
            double ds = Math.abs(rawTime - clip.startTime());
            double de = Math.abs(rawTime - clip.endTime());
            if (ds < bestDist) { bestDist = ds; bestSnap = clip.startTime(); }
            if (de < bestDist) { bestDist = de; bestSnap = clip.endTime(); }
        }
        if (bestDist < threshold) return bestSnap;

        // Snap to grid
        double grid = snapGridSize.get();
        if (grid > 0) {
            double snapped = Math.round(rawTime / grid) * grid;
            if (Math.abs(snapped - rawTime) < threshold) return snapped;
        }

        return rawTime;
    }

    // ==================== Viewport helpers ====================

    public void zoomViewAround(double centreTime, double factor) {
        double span = viewEnd.get() - viewStart.get();
        double newSpan = Math.max(dt.get() * 2, Math.min(span * factor, totalDuration.get() * 4));
        double newStart = centreTime - (centreTime - viewStart.get()) / span * newSpan;
        viewStart.set(Math.max(0, newStart));
        viewEnd.set(newStart + newSpan);
    }

    public void panView(double deltaMicros) {
        viewStart.set(viewStart.get() + deltaMicros);
        viewEnd.set(viewEnd.get() + deltaMicros);
    }

    public void fitView() {
        viewStart.set(0);
        viewEnd.set(totalDuration.get());
    }

    // ==================== Convenience factories ====================

    public SignalClip createDefaultClip(ClipShape shape, SignalChannel channel, double startTime) {
        double clipDuration = (viewEnd.get() - viewStart.get()) * 0.15;
        clipDuration = Math.max(dt.get() * 5, clipDuration);
        // Media extent is 2× the visible duration to allow trimming headroom
        double mediaDuration = clipDuration * 2.0;
        double mediaOffset = clipDuration * 0.5; // centered in the media
        double amplitude = defaultAmplitudeForChannel(channel);

        var params = defaultParamsForShape(shape, mediaDuration);
        List<SignalClip.SplinePoint> splinePoints = List.of();
        if (shape == ClipShape.SPLINE) {
            splinePoints = List.of(
                new SignalClip.SplinePoint(0.0, 0.0),
                new SignalClip.SplinePoint(0.5, 1.0),
                new SignalClip.SplinePoint(1.0, 0.0)
            );
        }

        return new SignalClip(
            UUID.randomUUID().toString(),
            channel, shape, startTime, clipDuration, amplitude,
            mediaOffset, mediaDuration, params, splinePoints
        );
    }

    /**
     * Create a clip with the given visible start and duration, centred within a
     * generous media extent. The waveform is defined over the full media, so the
     * visible portion shows the middle of the shape. Extending either edge reveals
     * more of the underlying waveform.
     */
    public SignalClip createClipCentred(ClipShape shape, SignalChannel channel, double startTime, double duration) {
        // Media is 4× the visible duration, clip is centred within it.
        // This gives plenty of headroom for extending in either direction.
        double mediaDuration = duration * 4.0;
        double mediaOffset = duration * 1.5; // centres the visible portion: (4-1)/2 = 1.5

        double amplitude = defaultAmplitudeForChannel(channel);
        var params = defaultParamsForShape(shape, mediaDuration);
        List<SignalClip.SplinePoint> splinePoints = List.of();
        if (shape == ClipShape.SPLINE) {
            splinePoints = List.of(
                new SignalClip.SplinePoint(0.0, 0.0),
                new SignalClip.SplinePoint(0.5, 1.0),
                new SignalClip.SplinePoint(1.0, 0.0)
            );
        }

        return new SignalClip(
            UUID.randomUUID().toString(),
            channel, shape, startTime, duration, amplitude,
            mediaOffset, mediaDuration, params, splinePoints
        );
    }

    private double defaultAmplitudeForChannel(SignalChannel channel) {
        return switch (channel) {
            case B1X, B1Y -> 15e-6;
            case GX, GZ   -> 0.02;
            case RF_GATE  -> 1.0;
        };
    }

    private Map<String, Double> defaultParamsForShape(ClipShape shape, double clipDuration) {
        return switch (shape) {
            case SINC -> Map.of("bandwidthHz", 4000.0, "centerOffset", 0.0, "windowFactor", 1.0);
            case TRAPEZOID -> Map.of("riseTime", clipDuration * 0.15, "flatTime", clipDuration * 0.5);
            case GAUSSIAN -> Map.of("sigma", clipDuration * 0.2);
            case TRIANGLE -> Map.of("peakPosition", 0.5);
            default -> Map.of();
        };
    }

    // ==================== Undo/Redo ====================

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
            var temp = new ArrayDeque<EditSnapshot>(MAX_UNDO);
            int count = 0;
            for (var snapshot : undoStack) {
                if (count++ >= MAX_UNDO) break;
                temp.push(snapshot);
            }
            undoStack.clear();
            var reversed = new ArrayDeque<EditSnapshot>(temp.size());
            while (!temp.isEmpty()) reversed.push(temp.pop());
            while (!reversed.isEmpty()) undoStack.push(reversed.pop());
        }
        redoStack.clear();
        updateUndoRedoFlags();
    }

    private EditSnapshot captureSnapshot() {
        return new EditSnapshot(
            List.copyOf(clips),
            dt.get(),
            totalDuration.get(),
            Set.copyOf(selectedClipIds),
            primarySelectedClipId.get(),
            List.copyOf(tracks)
        );
    }

    private void applySnapshot(EditSnapshot snapshot) {
        clips.setAll(snapshot.clips);
        dt.set(snapshot.dt);
        totalDuration.set(snapshot.totalDuration);
        selectedClipIds.clear();
        selectedClipIds.addAll(snapshot.selectedClipIds);
        primarySelectedClipId.set(snapshot.primarySelectedClipId);
        tracks.setAll(snapshot.tracks);
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
        List<SignalClip> clips,
        double dt,
        double totalDuration,
        Set<String> selectedClipIds,
        String primarySelectedClipId,
        List<EditorTrack> tracks
    ) {
        EditSnapshot {
            clips = List.copyOf(clips);
            selectedClipIds = Set.copyOf(selectedClipIds);
            tracks = List.copyOf(tracks);
        }
    }
}
