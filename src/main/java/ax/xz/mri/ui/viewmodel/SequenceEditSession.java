package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.circuit.ComponentTerminal;
import ax.xz.mri.model.sequence.ClipBaker;
import ax.xz.mri.model.sequence.ClipKind;
import ax.xz.mri.model.sequence.ClipSequence;
import ax.xz.mri.model.sequence.ClipShape;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.model.sequence.Track;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Mutable editing state for one clip-based sequence.
 *
 * <h3>Model</h3>
 * <p>The editor owns two intertwined lists:
 * <ul>
 *   <li><b>Tracks</b> — arrangement lanes, user-managed. Each track targets
 *       exactly one {@link SequenceChannel} from the active config. Multiple
 *       tracks may target the same output — their clips sum at bake time.</li>
 *   <li><b>Clips</b> — waveform-bearing pieces placed on a track
 *       ({@link SignalClip#trackId()}).</li>
 * </ul>
 *
 * <p>Both lists are persisted with the sequence and participate in undo.
 * Per-track collapse state is view-level (see {@link #collapsedTrackIds}) and
 * not undoable.
 *
 * <h3>Change notification</h3>
 * <p>The {@link #revision} counter is bumped on every mutation and drives
 * canvas redraws. External listeners may also observe the collection
 * properties directly.
 */
public final class SequenceEditSession {
    private static final int MAX_UNDO = 100;
    private static final double DEFAULT_DT = 10.0;
    private static final double DEFAULT_DURATION = 3000.0;
    private static final double SNAP_THRESHOLD_FRACTION = 0.005;

    // ── Core document state ──────────────────────────────────────────────────
    public final ObjectProperty<SequenceDocument> originalDocument = new SimpleObjectProperty<>();
    public final ObservableList<SignalClip> clips = FXCollections.observableArrayList();
    public final ObservableList<Track> tracks = FXCollections.observableArrayList();
    public final DoubleProperty dt = new SimpleDoubleProperty(DEFAULT_DT);
    public final DoubleProperty totalDuration = new SimpleDoubleProperty(DEFAULT_DURATION);
    public final IntegerProperty revision = new SimpleIntegerProperty(0);

    // ── Selection ────────────────────────────────────────────────────────────
    public final ObservableSet<String> selectedClipIds = FXCollections.observableSet(new LinkedHashSet<>());
    /** Most recently clicked clip — drives the inspector. */
    public final ObjectProperty<String> primarySelectedClipId = new SimpleObjectProperty<>(null);

    // ── View-level preferences (not undoable) ───────────────────────────────
    /** Track ids currently rendered as a thin collapsed bar. */
    public final ObservableSet<String> collapsedTrackIds = FXCollections.observableSet(new LinkedHashSet<>());

    // ── Viewport (μs) ───────────────────────────────────────────────────────
    public final DoubleProperty viewStart = new SimpleDoubleProperty(0);
    public final DoubleProperty viewEnd = new SimpleDoubleProperty(DEFAULT_DURATION);

    // ── Active simulation config ────────────────────────────────────────────
    public final ObjectProperty<ProjectNodeId> activeSimConfigId = new SimpleObjectProperty<>();
    /** Resolved config snapshot. Drives channel resolution + default amplitudes. */
    public final ObjectProperty<SimulationConfig> activeConfig = new SimpleObjectProperty<>();
    private ProjectNodeId originalSimConfigId;

    /** Repository supplier — used to resolve eigenfield metadata (display units). */
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
        // When the active config changes we do NOT stomp the user's track
        // arrangement — tracks are state. We only prune collapse-state entries
        // that became invalid (track deleted elsewhere).
        tracks.addListener((javafx.collections.ListChangeListener<Track>) c -> {
            var valid = new LinkedHashSet<String>();
            for (var t : tracks) valid.add(t.id());
            collapsedTrackIds.removeIf(id -> !valid.contains(id));
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Document loading / saving
    // ══════════════════════════════════════════════════════════════════════════

    /** Load a sequence document for editing. Clears undo history. */
    public void open(SequenceDocument document) {
        originalDocument.set(document);

        var circuit = activeCircuit();
        ClipSequence clipSeq = document.clipSequence();
        if (clipSeq == null) {
            // A sequence created through the wizard always carries a clip
            // sequence; legacy documents without one start empty with the
            // circuit's default tracks.
            clipSeq = new ClipSequence(10.0, 1000.0, ClipBaker.defaultTracksFor(circuit), List.of());
        }
        var loadedTracks = clipSeq.tracks().isEmpty() ? ClipBaker.defaultTracksFor(circuit) : clipSeq.tracks();
        tracks.setAll(loadedTracks);
        clips.setAll(clipSeq.clips());
        dt.set(clipSeq.dt());
        totalDuration.set(clipSeq.totalDuration());

        selectedClipIds.clear();
        primarySelectedClipId.set(null);
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
        var clipSeq = new ClipSequence(dt.get(), totalDuration.get(),
            List.copyOf(tracks), List.copyOf(clips));
        var baked = ClipBaker.bake(clipSeq, activeCircuit());
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
                || !tracks.equals(orig.clipSequence().tracks())
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
    // Active config
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Replace the live config snapshot. Tracks are left alone — they are
     * user-managed state, so changing the config does not blow away
     * arrangement. Tracks whose output channel no longer exists in the new
     * config still render; the inspector surfaces them as "orphan" so the user
     * can re-target or delete.
     */
    public void applyActiveConfig(SimulationConfig config) {
        if (Objects.equals(activeConfig.get(), config)) return;
        activeConfig.set(config);
        bumpRevision();
    }

    /** Resolve the {@link CircuitDocument} backing the active sim-config, or {@code null}. */
    public CircuitDocument activeCircuit() {
        var repo = repositorySupplier.get();
        var cfg = activeConfig.get();
        if (repo == null || cfg == null || cfg.circuitId() == null) return null;
        return repo.circuit(cfg.circuitId());
    }

    /** Look up the voltage source backing an output channel, or {@code null}. */
    public CircuitComponent.VoltageSource sourceForChannel(SequenceChannel channel) {
        if (channel == null) return null;
        var circuit = activeCircuit();
        if (circuit == null) return null;
        for (var src : circuit.voltageSources()) {
            if (src.name().equals(channel.sourceName()) && channel.subIndex() < src.channelCount()) {
                return src;
            }
        }
        return null;
    }

    /** Resolve the eigenfield document reachable from a channel's source (through switches), or {@code null}. */
    public EigenfieldDocument eigenfieldForChannel(SequenceChannel channel) {
        var src = sourceForChannel(channel);
        if (src == null || src.isGate()) return null;
        var circuit = activeCircuit();
        if (circuit == null) return null;
        var coil = coilReachableFromSource(circuit, src.id());
        if (coil == null) return null;
        var repo = repositorySupplier.get();
        if (repo == null) return null;
        return repo.node(coil.eigenfieldId()) instanceof EigenfieldDocument ef ? ef : null;
    }

    /** Display units declared by the eigenfield behind a channel, or empty string. */
    public String unitsForChannel(SequenceChannel channel) {
        var ef = eigenfieldForChannel(channel);
        return ef != null ? ef.units() : "";
    }

    /** List of all addressable output channels in the active circuit (empty if no circuit). */
    public List<SequenceChannel> availableOutputChannels() {
        var circuit = activeCircuit();
        if (circuit == null) return List.of();
        var out = new ArrayList<SequenceChannel>();
        for (var src : circuit.voltageSources()) {
            int count = src.channelCount();
            for (int sub = 0; sub < count; sub++) out.add(SequenceChannel.of(src.name(), sub));
        }
        return List.copyOf(out);
    }

    /** BFS through wires (and transparently through switches) to find a coil reachable from the source. */
    private static CircuitComponent.Coil coilReachableFromSource(CircuitDocument circuit, ComponentId sourceId) {
        var adjacency = new HashMap<ComponentTerminal, List<ComponentTerminal>>();
        for (var w : circuit.wires()) {
            adjacency.computeIfAbsent(w.from(), k -> new ArrayList<>()).add(w.to());
            adjacency.computeIfAbsent(w.to(), k -> new ArrayList<>()).add(w.from());
        }
        var componentsById = new HashMap<ComponentId, CircuitComponent>();
        for (var c : circuit.components()) componentsById.put(c.id(), c);

        var visited = new HashSet<ComponentTerminal>();
        var queue = new ArrayDeque<ComponentTerminal>();
        queue.add(new ComponentTerminal(sourceId, "a"));
        queue.add(new ComponentTerminal(sourceId, "b"));
        while (!queue.isEmpty()) {
            var term = queue.poll();
            if (!visited.add(term)) continue;
            var component = componentsById.get(term.componentId());
            if (component instanceof CircuitComponent.Coil coil && !term.componentId().equals(sourceId)) {
                return coil;
            }
            if (component instanceof CircuitComponent.SwitchComponent && (term.port().equals("a") || term.port().equals("b"))) {
                String other = term.port().equals("a") ? "b" : "a";
                queue.add(new ComponentTerminal(term.componentId(), other));
            }
            for (var neighbour : adjacency.getOrDefault(term, List.of())) queue.add(neighbour);
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Track management (all push undo)
    // ══════════════════════════════════════════════════════════════════════════

    public Track findTrack(String trackId) {
        if (trackId == null) return null;
        for (var t : tracks) if (t.id().equals(trackId)) return t;
        return null;
    }

    private int indexOfTrack(String trackId) {
        for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).id().equals(trackId)) return i;
        return -1;
    }

    /** Add a new track targeting a specific output channel. */
    public Track addTrack(SequenceChannel outputChannel, String name) {
        pushUndo();
        var track = new Track(outputChannel, name != null ? name : defaultTrackNameFor(outputChannel));
        tracks.add(track);
        bumpRevision();
        return track;
    }

    /** Remove a track and all its clips. */
    public void removeTrack(String trackId) {
        int idx = indexOfTrack(trackId);
        if (idx < 0) return;
        pushUndo();
        tracks.remove(idx);
        clips.removeIf(c -> trackId.equals(c.trackId()));
        selectedClipIds.removeIf(id -> {
            var clip = findClip(id);
            return clip == null;
        });
        if (primarySelectedClipId.get() != null && findClip(primarySelectedClipId.get()) == null) {
            primarySelectedClipId.set(selectedClipIds.isEmpty() ? null : selectedClipIds.iterator().next());
        }
        bumpRevision();
    }

    public void renameTrack(String trackId, String newName) {
        int idx = indexOfTrack(trackId);
        if (idx < 0) return;
        pushUndo();
        tracks.set(idx, tracks.get(idx).withName(newName == null ? "" : newName));
        bumpRevision();
    }

    public void setTrackOutputChannel(String trackId, SequenceChannel newChannel) {
        int idx = indexOfTrack(trackId);
        if (idx < 0) return;
        pushUndo();
        tracks.set(idx, tracks.get(idx).withOutputChannel(newChannel));
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

    /** Toggle collapsed state on a track (view-level, not undoable). */
    public void setTrackCollapsed(String trackId, boolean collapsed) {
        if (collapsed) collapsedTrackIds.add(trackId);
        else collapsedTrackIds.remove(trackId);
        bumpRevision();
    }

    public boolean isTrackCollapsed(String trackId) {
        return collapsedTrackIds.contains(trackId);
    }

    /** Best-effort default name for a new track targeting an output channel. */
    public String defaultTrackNameFor(SequenceChannel channel) {
        var src = sourceForChannel(channel);
        if (src == null) return channel.sourceName() + "[" + channel.subIndex() + "]";
        return ClipBaker.defaultTrackName(src, channel.subIndex());
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

    /** Select clips overlapping a time×track region (rubber-band). */
    public void selectClipsInRegion(double timeStart, double timeEnd, Set<String> trackIds, boolean addToExisting) {
        if (!addToExisting) selectedClipIds.clear();
        double tMin = Math.min(timeStart, timeEnd);
        double tMax = Math.max(timeStart, timeEnd);
        String first = null;
        for (var clip : clips) {
            if (trackIds.contains(clip.trackId()) && clip.endTime() > tMin && clip.startTime() < tMax) {
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

    /** All clips on a specific track, in timeline order. */
    public List<SignalClip> clipsOnTrack(String trackId) {
        return clips.stream()
            .filter(c -> c.trackId().equals(trackId))
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
            clip.id(), clip.trackId(), clip.shape(),
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

    /** Move a clip from one track to another (cross-track drag). */
    public void changeClipTrack(String clipId, String newTrackId) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        if (findTrack(newTrackId) == null) return;
        pushUndo();
        clips.set(idx, clips.get(idx).withTrack(newTrackId));
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
    public SignalClip createDefaultClip(ClipKind kind, String trackId, double startTime) {
        double clipDuration = Math.max(dt.get() * 5, (viewEnd.get() - viewStart.get()) * 0.15);
        var track = findTrack(trackId);
        return SignalClip.freshCentred(trackId, kind, startTime, clipDuration,
            track != null ? defaultAmplitudeForChannel(track.outputChannel()) : 1.0);
    }

    /** Create a clip with an explicit duration (used by click-and-drag creation). */
    public SignalClip createClipCentred(ClipKind kind, String trackId, double startTime, double duration) {
        var track = findTrack(trackId);
        return SignalClip.freshCentred(trackId, kind, startTime, duration,
            track != null ? defaultAmplitudeForChannel(track.outputChannel()) : 1.0);
    }

    /**
     * Sensible starting amplitude for a clip targeting a given output channel,
     * driven by the active circuit's voltage-source max amplitude so new clips
     * land on a visible part of the axis.
     */
    public double defaultAmplitudeForChannel(SequenceChannel channel) {
        var src = sourceForChannel(channel);
        if (src == null) return 1.0;
        double m = Math.max(Math.abs(src.minAmplitude()), Math.abs(src.maxAmplitude()));
        if (m == 0) return 1.0;
        return src.minAmplitude() < 0 ? 0.66 * src.maxAmplitude() : src.maxAmplitude();
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
            List.copyOf(tracks),
            dt.get(),
            totalDuration.get(),
            Set.copyOf(selectedClipIds),
            primarySelectedClipId.get(),
            activeSimConfigId.get()
        );
    }

    private void applySnapshot(EditSnapshot s) {
        clips.setAll(s.clips);
        tracks.setAll(s.tracks);
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
        List<Track> tracks,
        double dt,
        double totalDuration,
        Set<String> selectedClipIds,
        String primarySelectedClipId,
        ProjectNodeId activeSimConfigId
    ) {
        EditSnapshot {
            clips = List.copyOf(clips);
            tracks = List.copyOf(tracks);
            selectedClipIds = Set.copyOf(selectedClipIds);
        }
    }

    // Retain the old ID-based identifier type for callers that pass auto-generated ids.
    @SuppressWarnings("unused")
    private static String newId() { return UUID.randomUUID().toString(); }
}
