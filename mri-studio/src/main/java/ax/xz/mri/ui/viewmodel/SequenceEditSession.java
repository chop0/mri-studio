package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.sequence.ClipBaker;
import ax.xz.mri.model.sequence.ClipKind;
import ax.xz.mri.model.sequence.ClipSequence;
import ax.xz.mri.model.sequence.ClipShape;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.model.simulation.FieldDefinition;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
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
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Mutable editing state for one clip-based sequence.
 *
 * <h3>What lives here</h3>
 * <ul>
 *   <li><b>Clips</b> — the edited collection of {@link SignalClip}s, with
 *       multi-clip selection and a designated "primary" clip for the inspector.</li>
 *   <li><b>Viewport</b> — the editor's own zoom/pan range in microseconds.</li>
 *   <li><b>Active config</b> — which {@link SimulationConfig} drives track
 *       layout, channel colouring, and default amplitudes.</li>
 *   <li><b>Derived tracks</b> — an observable, read-only lane list rebuilt from
 *       the active config whenever it changes. Tracks are <em>view</em>, not
 *       state: there is exactly one lane per
 *       {@link FieldDefinition#kind() field channel} slot plus one RF-gate lane,
 *       in config order. Collapse state is tracked per-channel (see
 *       {@link #collapsedChannels}).</li>
 *   <li><b>Undo/redo</b> — every mutation pushes a snapshot before applying the
 *       change; tracks are not part of the snapshot because they are derived.</li>
 * </ul>
 *
 * <h3>Change notification</h3>
 * <p>The {@link #revision} counter is bumped on every mutation and drives canvas
 * redraws. External listeners may also observe the collection properties
 * directly.
 */
public final class SequenceEditSession {
    private static final int MAX_UNDO = 100;
    private static final double DEFAULT_DT = 10.0;            // μs
    private static final double DEFAULT_DURATION = 3000.0;     // μs
    private static final double SNAP_THRESHOLD_FRACTION = 0.005; // 0.5% of visible span

    // ── Core document state ──────────────────────────────────────────────────
    public final ObjectProperty<SequenceDocument> originalDocument = new SimpleObjectProperty<>();
    public final ObservableList<SignalClip> clips = FXCollections.observableArrayList();
    public final DoubleProperty dt = new SimpleDoubleProperty(DEFAULT_DT);
    public final DoubleProperty totalDuration = new SimpleDoubleProperty(DEFAULT_DURATION);
    public final IntegerProperty revision = new SimpleIntegerProperty(0);

    // ── Selection ────────────────────────────────────────────────────────────
    public final ObservableSet<String> selectedClipIds = FXCollections.observableSet(new LinkedHashSet<>());
    /** Most recently clicked clip — drives the inspector. */
    public final ObjectProperty<String> primarySelectedClipId = new SimpleObjectProperty<>(null);

    // ── Tracks (derived from activeConfig) + collapse state ─────────────────
    /** Read-only observable view of the current lane list. Rebuilt when {@link #activeConfig} changes. */
    public final ObservableList<EditorTrack> tracks = FXCollections.observableArrayList();
    /** Channels currently rendered as thin collapsed bars. View-layer preference; not part of undo. */
    public final ObservableSet<SequenceChannel> collapsedChannels = FXCollections.observableSet(new LinkedHashSet<>());

    // ── Viewport (editor's own μs range) ─────────────────────────────────────
    public final DoubleProperty viewStart = new SimpleDoubleProperty(0);
    public final DoubleProperty viewEnd = new SimpleDoubleProperty(DEFAULT_DURATION);

    // ── Active simulation config ─────────────────────────────────────────────
    public final ObjectProperty<ProjectNodeId> activeSimConfigId = new SimpleObjectProperty<>();
    /** Resolved config snapshot. Drives track layout, default amplitudes and baking. Nullable. */
    public final ObjectProperty<SimulationConfig> activeConfig = new SimpleObjectProperty<>();
    private ProjectNodeId originalSimConfigId;

    /** Repository supplier — used to resolve eigenfield metadata (units, defaultMagnitude). */
    private Supplier<ProjectRepository> repositorySupplier = () -> null;

    // ── Snapping ─────────────────────────────────────────────────────────────
    public final BooleanProperty snapEnabled = new SimpleBooleanProperty(true);
    public final DoubleProperty snapGridSize = new SimpleDoubleProperty(0);

    // ── Undo/Redo ────────────────────────────────────────────────────────────
    private final BooleanProperty canUndo = new SimpleBooleanProperty(false);
    private final BooleanProperty canRedo = new SimpleBooleanProperty(false);
    private final Deque<EditSnapshot> undoStack = new ArrayDeque<>();
    private final Deque<EditSnapshot> redoStack = new ArrayDeque<>();

    public ReadOnlyBooleanProperty canUndoProperty() { return canUndo; }
    public ReadOnlyBooleanProperty canRedoProperty() { return canRedo; }

    public SequenceEditSession() {
        // Keep tracks in sync with activeConfig automatically — the edit layer
        // never needs to manually rebuild them, and we never end up out of sync.
        activeConfig.addListener((obs, oldCfg, newCfg) -> rebuildDerivedTracks(newCfg));
        rebuildDerivedTracks(null);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Document loading / saving
    // ══════════════════════════════════════════════════════════════════════════

    /** Load a sequence document for editing. Clears undo history. */
    public void open(SequenceDocument document) {
        originalDocument.set(document);

        ClipSequence clipSeq = document.clipSequence();
        var config = activeConfig.get();
        if (clipSeq != null) {
            clips.setAll(clipSeq.clips());
            dt.set(clipSeq.dt());
            totalDuration.set(clipSeq.totalDuration());
        } else {
            var converted = ClipBaker.fromLegacy(document.segments(), document.pulse(), config);
            clips.setAll(converted.clips());
            dt.set(converted.dt());
            totalDuration.set(converted.totalDuration());
        }

        selectedClipIds.clear();
        primarySelectedClipId.set(null);
        viewStart.set(0);
        viewEnd.set(totalDuration.get());
        undoStack.clear();
        redoStack.clear();
        canUndo.set(false);
        canRedo.set(false);
        rebuildDerivedTracks(config);
        bumpRevision();
    }

    /** Build an immutable document from the current editing state, baking clips to steps. */
    public SequenceDocument toDocument() {
        var orig = originalDocument.get();
        var clipSeq = new ClipSequence(dt.get(), totalDuration.get(), List.copyOf(clips));
        var baked = ClipBaker.bake(clipSeq, activeConfig.get());
        return new SequenceDocument(
            orig.id(), orig.name(),
            baked.segments(), baked.pulseSegments(),
            clipSeq, activeSimConfigId.get()
        );
    }

    public boolean isDirty() {
        var orig = originalDocument.get();
        if (orig == null) return false;
        if (!Objects.equals(activeSimConfigId.get(), originalSimConfigId)) return true;
        if (orig.clipSequence() != null) {
            return !clips.equals(orig.clipSequence().clips())
                || dt.get() != orig.clipSequence().dt()
                || totalDuration.get() != orig.clipSequence().totalDuration();
        }
        return true;
    }

    /** Establish the "last-saved" config baseline after a load. */
    public void setOriginalSimConfigId(ProjectNodeId configId) {
        this.originalSimConfigId = configId;
        this.activeSimConfigId.set(configId);
    }

    /** Provide a repository supplier so we can resolve eigenfield metadata on demand. */
    public void setRepositorySupplier(Supplier<ProjectRepository> supplier) {
        this.repositorySupplier = supplier != null ? supplier : () -> null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Active config + derived tracks
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Replace the live config snapshot driving track layout, channel colouring
     * and default amplitudes. Tracks are rebuilt automatically; collapse state
     * for channels that no longer exist is pruned.
     */
    public void applyActiveConfig(SimulationConfig config) {
        if (Objects.equals(activeConfig.get(), config)) return;
        activeConfig.set(config);
        bumpRevision();
    }

    private void rebuildDerivedTracks(SimulationConfig config) {
        var next = EditorTrack.forConfig(config);
        tracks.setAll(next);
        // Prune collapse entries for channels that no longer exist.
        var valid = new LinkedHashSet<SequenceChannel>();
        for (var t : next) valid.add(t.channel());
        collapsedChannels.removeIf(ch -> !valid.contains(ch));
    }

    /** Toggle or set the collapse state for a channel. View-layer preference; not undoable. */
    public void setChannelCollapsed(SequenceChannel channel, boolean collapsed) {
        if (collapsed) collapsedChannels.add(channel);
        else collapsedChannels.remove(channel);
        bumpRevision();
    }

    public boolean isChannelCollapsed(SequenceChannel channel) {
        return collapsedChannels.contains(channel);
    }

    /** Look up the {@link FieldDefinition} backing a channel in the active config, or {@code null}. */
    public FieldDefinition fieldForChannel(SequenceChannel channel) {
        if (channel == null || channel.isRfGate()) return null;
        var config = activeConfig.get();
        if (config == null) return null;
        for (var f : config.fields()) {
            if (f.name().equals(channel.fieldName()) && channel.subIndex() < f.kind().channelCount()) {
                return f;
            }
        }
        return null;
    }

    /** Resolve the eigenfield document behind a channel, or {@code null} if unavailable. */
    public EigenfieldDocument eigenfieldForChannel(SequenceChannel channel) {
        if (channel == null || channel.isRfGate()) return null;
        var field = fieldForChannel(channel);
        if (field == null || field.eigenfieldId() == null) return null;
        var repo = repositorySupplier.get();
        if (repo == null) return null;
        return repo.node(field.eigenfieldId()) instanceof EigenfieldDocument ef ? ef : null;
    }

    /** Display units declared by the eigenfield behind a channel, or empty string. */
    public String unitsForChannel(SequenceChannel channel) {
        var ef = eigenfieldForChannel(channel);
        return ef != null ? ef.units() : "";
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Selection
    // ══════════════════════════════════════════════════════════════════════════

    public boolean isSelected(String clipId) { return selectedClipIds.contains(clipId); }

    public List<SignalClip> selectedClips() {
        return clips.stream().filter(c -> selectedClipIds.contains(c.id())).toList();
    }

    public SignalClip primarySelectedClip() { return findClip(primarySelectedClipId.get()); }

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
        if (clipId == null) return;
        selectedClipIds.add(clipId);
        primarySelectedClipId.set(clipId);
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

    /** Select clips overlapping a time×channel region (rubber-band). */
    public void selectClipsInRegion(double timeStart, double timeEnd, Set<SequenceChannel> channels, boolean addToExisting) {
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

    public void selectAll() {
        selectedClipIds.clear();
        for (var clip : clips) selectedClipIds.add(clip.id());
        if (!clips.isEmpty()) primarySelectedClipId.set(clips.getFirst().id());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Clip queries
    // ══════════════════════════════════════════════════════════════════════════

    public SignalClip findClip(String clipId) {
        if (clipId == null) return null;
        for (var clip : clips) if (clip.id().equals(clipId)) return clip;
        return null;
    }

    private int indexOfClip(String clipId) {
        for (int i = 0; i < clips.size(); i++) if (clips.get(i).id().equals(clipId)) return i;
        return -1;
    }

    public List<SignalClip> clipsOnChannel(SequenceChannel channel) {
        return clips.stream()
            .filter(c -> c.channel().equals(channel))
            .sorted((a, b) -> Double.compare(a.startTime(), b.startTime()))
            .toList();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Clip mutations (all push undo)
    // ══════════════════════════════════════════════════════════════════════════

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

    public void resizeClip(String clipId, double newDuration) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        var clip = clips.get(idx);
        newDuration = Math.max(dt.get(), newDuration);
        double neededMedia = clip.mediaOffset() + newDuration;
        pushUndo();
        if (neededMedia > clip.mediaDuration()) {
            clips.set(idx, clip.withDuration(newDuration).withMediaDuration(neededMedia));
        } else {
            clips.set(idx, clip.withDuration(newDuration));
        }
        bumpRevision();
    }

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
            newMediaDuration += -newMediaOffset;
            newMediaOffset = 0;
        }
        pushUndo();
        clips.set(idx, new SignalClip(
            clip.id(), clip.channel(), clip.shape(),
            newStartTime, newDuration, clip.amplitude(),
            newMediaOffset, newMediaDuration
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

    /** Replace a clip's shape outright (and its typed parameters). */
    public void setClipShape(String clipId, ClipShape newShape) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        pushUndo();
        clips.set(idx, clips.get(idx).withShape(newShape));
        bumpRevision();
    }

    /** Morph a clip to a different shape kind, using that kind's defaults for the current media duration. */
    public void changeClipKind(String clipId, ClipKind newKind) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        pushUndo();
        var clip = clips.get(idx);
        clips.set(idx, clip.withShape(newKind.defaultFor(clip.mediaDuration())));
        bumpRevision();
    }

    public void changeClipChannel(String clipId, SequenceChannel newChannel) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        pushUndo();
        clips.set(idx, clips.get(idx).withChannel(newChannel));
        bumpRevision();
    }

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

    public void setActiveSimConfig(ProjectNodeId configId) {
        if (Objects.equals(configId, activeSimConfigId.get())) return;
        pushUndo();
        activeSimConfigId.set(configId);
        bumpRevision();
    }

    public void setTotalDuration(double duration) {
        if (duration <= 0) return;
        pushUndo();
        totalDuration.set(duration);
        if (viewEnd.get() > duration) viewEnd.set(duration);
        bumpRevision();
    }

    public void recentreClip(String clipId) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        pushUndo();
        clips.set(idx, clips.get(idx).centred());
        bumpRevision();
    }

    public void replaceClip(String clipId, SignalClip replacement) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        pushUndo();
        clips.set(idx, replacement);
        bumpRevision();
    }

    // ── Spline points ────────────────────────────────────────────────────────

    public void updateSplinePoint(String clipId, int pointIndex, ClipShape.Spline.Point newPoint) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        var clip = clips.get(idx);
        if (!(clip.shape() instanceof ClipShape.Spline spline)) return;
        if (pointIndex < 0 || pointIndex >= spline.points().size()) return;
        pushUndo();
        var points = new ArrayList<>(spline.points());
        points.set(pointIndex, newPoint);
        clips.set(idx, clip.withShape(spline.withPoints(points)));
        bumpRevision();
    }

    public void addSplinePoint(String clipId, ClipShape.Spline.Point point) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        var clip = clips.get(idx);
        if (!(clip.shape() instanceof ClipShape.Spline spline)) return;
        pushUndo();
        var points = new ArrayList<>(spline.points());
        int insertAt = 0;
        while (insertAt < points.size() && points.get(insertAt).t() < point.t()) insertAt++;
        points.add(insertAt, point);
        clips.set(idx, clip.withShape(spline.withPoints(points)));
        bumpRevision();
    }

    public void removeSplinePoint(String clipId, int pointIndex) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        var clip = clips.get(idx);
        if (!(clip.shape() instanceof ClipShape.Spline spline)) return;
        if (pointIndex < 0 || pointIndex >= spline.points().size()) return;
        if (spline.points().size() <= 2) return;
        pushUndo();
        var points = new ArrayList<>(spline.points());
        points.remove(pointIndex);
        clips.set(idx, clip.withShape(spline.withPoints(points)));
        bumpRevision();
    }

    // ── Split ────────────────────────────────────────────────────────────────

    public void splitClip(String clipId, double splitTime) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        var clip = clips.get(idx);
        if (splitTime <= clip.startTime() || splitTime >= clip.endTime()) return;

        pushUndo();
        var split = clip.split(splitTime);
        clips.set(idx, split.left());
        clips.add(idx + 1, split.right());
        selectedClipIds.clear();
        selectedClipIds.add(split.left().id());
        selectedClipIds.add(split.right().id());
        primarySelectedClipId.set(split.left().id());
        bumpRevision();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Snapping
    // ══════════════════════════════════════════════════════════════════════════

    public double snapTime(double rawTime) {
        if (!snapEnabled.get()) return rawTime;
        double viewSpan = viewEnd.get() - viewStart.get();
        double threshold = viewSpan * SNAP_THRESHOLD_FRACTION;

        double bestSnap = rawTime;
        double bestDist = threshold;
        for (var clip : clips) {
            double ds = Math.abs(rawTime - clip.startTime());
            double de = Math.abs(rawTime - clip.endTime());
            if (ds < bestDist) { bestDist = ds; bestSnap = clip.startTime(); }
            if (de < bestDist) { bestDist = de; bestSnap = clip.endTime(); }
        }
        if (bestDist < threshold) return bestSnap;

        double grid = snapGridSize.get();
        if (grid > 0) {
            double snapped = Math.round(rawTime / grid) * grid;
            if (Math.abs(snapped - rawTime) < threshold) return snapped;
        }
        return rawTime;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Viewport helpers
    // ══════════════════════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════════════════════
    // Clip creation factories
    // ══════════════════════════════════════════════════════════════════════════

    /** Create a clip with a duration equal to 15 % of the visible span. */
    public SignalClip createDefaultClip(ClipKind kind, SequenceChannel channel, double startTime) {
        double clipDuration = Math.max(dt.get() * 5, (viewEnd.get() - viewStart.get()) * 0.15);
        return SignalClip.freshCentred(channel, kind, startTime, clipDuration, defaultAmplitudeForChannel(channel));
    }

    /** Create a clip with an explicit duration (used by click-and-drag creation). */
    public SignalClip createClipCentred(ClipKind kind, SequenceChannel channel, double startTime, double duration) {
        return SignalClip.freshCentred(channel, kind, startTime, duration, defaultAmplitudeForChannel(channel));
    }

    /**
     * Sensible starting amplitude for a clip on the given channel — driven by
     * the active config's {@link FieldDefinition#maxAmplitude()} so a new clip
     * lands on a visible part of the axis instead of at zero.
     */
    public double defaultAmplitudeForChannel(SequenceChannel channel) {
        if (channel.isRfGate()) return 1.0;
        var field = fieldForChannel(channel);
        if (field == null) return 1.0;
        double m = Math.max(Math.abs(field.minAmplitude()), Math.abs(field.maxAmplitude()));
        if (m == 0) return 1.0;
        return field.minAmplitude() < 0 ? 0.66 * field.maxAmplitude() : field.maxAmplitude();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Undo / Redo
    // ══════════════════════════════════════════════════════════════════════════

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
        while (undoStack.size() > MAX_UNDO) undoStack.removeLast();
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
            activeSimConfigId.get()
        );
    }

    private void applySnapshot(EditSnapshot s) {
        clips.setAll(s.clips);
        dt.set(s.dt);
        totalDuration.set(s.totalDuration);
        selectedClipIds.clear();
        selectedClipIds.addAll(s.selectedClipIds);
        primarySelectedClipId.set(s.primarySelectedClipId);
        activeSimConfigId.set(s.activeSimConfigId);
        bumpRevision();
    }

    private void updateUndoRedoFlags() {
        canUndo.set(!undoStack.isEmpty());
        canRedo.set(!redoStack.isEmpty());
    }

    private void bumpRevision() { revision.set(revision.get() + 1); }

    private record EditSnapshot(
        List<SignalClip> clips,
        double dt,
        double totalDuration,
        Set<String> selectedClipIds,
        String primarySelectedClipId,
        ProjectNodeId activeSimConfigId
    ) {
        EditSnapshot {
            clips = List.copyOf(clips);
            selectedClipIds = Set.copyOf(selectedClipIds);
        }
    }
}
