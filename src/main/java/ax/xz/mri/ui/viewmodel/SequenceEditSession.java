package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.CircuitDocument;
import ax.xz.mri.model.circuit.CircuitGraph;
import ax.xz.mri.model.sequence.ClipBaker;
import ax.xz.mri.model.sequence.ClipKind;
import ax.xz.mri.model.sequence.ClipSequence;
import ax.xz.mri.model.sequence.ClipShape;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.model.sequence.Track;
import ax.xz.mri.model.simulation.MultiProbeSignalTrace;
import ax.xz.mri.model.simulation.SimulationConfig;
import ax.xz.mri.hardware.HardwarePluginRegistry;
import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.HardwareConfigDocument;
import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.project.SequenceDocument;
import ax.xz.mri.util.MathUtil;
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
    /**
     * Shared viewport state. Production wires the studio's global
     * {@link ViewportViewModel} via {@link #setViewport}; tests get the
     * default private instance so they don't need to know about it.
     */
    private ViewportViewModel viewport = new ViewportViewModel();

    // ── Active simulation config ────────────────────────────────────────────
    public final ObjectProperty<ProjectNodeId> activeSimConfigId = new SimpleObjectProperty<>();

    /**
     * Currently bound hardware config for this sequence — drives "Run on hardware"
     * + the routing menus.
     *
     * <p>This is the <em>id</em>, not the document. The document is resolved through
     * the project repository on demand via {@link #activeHardwareConfigDoc()}. Holding
     * a doc reference here is a state-management trap: when the repo replaces the
     * document (rename, save, in-place edit), every cached reference goes stale, the
     * UI shows the old name, and re-binding through "==" comparisons silently fails.
     * The id is stable across all those mutations.
     */
    public final ObjectProperty<ProjectNodeId> activeHardwareConfigId = new SimpleObjectProperty<>();

    /** Whether to show the read-only sim probe-output rows beneath the editable tracks. */
    public final ObservableSet<String> enabledSimOutputs = FXCollections.observableSet(new LinkedHashSet<>());
    /** Probe names whose hardware-output rows are visible beneath the editable tracks. */
    public final ObservableSet<String> enabledHardwareOutputs = FXCollections.observableSet(new LinkedHashSet<>());

    /**
     * Per-row height (px) for the read-only output band. View-level (not
     * undoable, not persisted to the document) — the user adjusts it by
     * dragging the divider above the output band in the timeline canvas.
     * Defaults match the historical fixed value so existing layouts don't
     * jump on first open.
     */
    public final DoubleProperty outputRowHeight = new SimpleDoubleProperty(30.0);

    /** Last simulator probe traces — kept separately from {@link #lastHardwareTraces} so both can render side-by-side. */
    public final ObjectProperty<MultiProbeSignalTrace> lastSimulationTraces = new SimpleObjectProperty<>();
    /** Last hardware probe traces — populated by the hardware run session. */
    public final ObjectProperty<MultiProbeSignalTrace> lastHardwareTraces = new SimpleObjectProperty<>();
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

    public ViewportViewModel viewport() { return viewport; }

    /** Wire the studio's shared viewport. Production calls this once after construction. */
    public void setViewport(ViewportViewModel viewport) {
        this.viewport = viewport;
        viewport.setMaxTimePreservePosition(totalDuration.get());
    }

    public SequenceEditSession() {
        // When the active config changes we do NOT stomp the user's track
        // arrangement — tracks are state. We only prune collapse-state entries
        // that became invalid (track deleted elsewhere).
        tracks.addListener((javafx.collections.ListChangeListener<Track>) c -> {
            var valid = new LinkedHashSet<String>();
            for (var t : tracks) valid.add(t.id());
            collapsedTrackIds.removeIf(id -> !valid.contains(id));
        });
        // Hardware traces are device-specific — keeping the previous device's
        // probe trace around after the user picks a different (or no) config
        // would render misleading rows. Clear the trace and any per-probe
        // enables that no longer correspond to the new device's capabilities.
        //
        // Listening on the id (not the doc) means in-place edits to the same
        // bound config don't pointlessly clear traces or rebind probe enables —
        // the binding hasn't changed, only the device's settings have.
        activeHardwareConfigId.addListener((obs, oldId, newId) -> {
            if (Objects.equals(oldId, newId)) return;
            lastHardwareTraces.set(null);
            var newCfg = activeHardwareConfigDoc();
            // Drop enables that aren't valid for the new device. With no
            // device, drop everything — all rows would render as "no trace
            // available" placeholders, which is just visual noise.
            if (newCfg == null || newCfg.config() == null) {
                enabledHardwareOutputs.clear();
            } else {
                var plugin = HardwarePluginRegistry.byId(newCfg.config().pluginId()).orElse(null);
                if (plugin != null) {
                    var validProbes = new LinkedHashSet<>(plugin.capabilities().probeNames());
                    enabledHardwareOutputs.removeIf(name -> !validProbes.contains(name));
                    // Auto-enable every advertised probe when a config is freshly
                    // bound — otherwise the trace pane has nothing to plot until
                    // the user finds the Outputs popover and ticks it themselves.
                    enabledHardwareOutputs.addAll(validProbes);
                }
            }
            // Drop hardware routings that aren't valid in the new context.
            // Routings to a channel the new device doesn't expose wouldn't
            // bake correctly anyway — better to surface that as an unrouted
            // track in the editor than a hidden mismatch at run time.
            var validOutputs = new LinkedHashSet<SequenceChannel>(availableHardwareChannels(newCfg));
            for (int i = 0; i < tracks.size(); i++) {
                var t = tracks.get(i);
                var hwCh = t.hardwareChannel();
                if (hwCh == null) continue;
                if (!validOutputs.contains(hwCh)) {
                    // Note: deliberately NOT routing through mutate() — this
                    // is a side-effect of changing the bound config, not a
                    // user edit, so it shouldn't be undoable on its own.
                    tracks.set(i, t.withHardwareChannel(null));
                }
            }
            bumpRevision();
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Document loading / saving
    // ══════════════════════════════════════════════════════════════════════════

    /** Load a sequence document for editing. Clears undo history. */
    public void open(SequenceDocument document) {
        originalDocument.set(document);

        var circuit = activeCircuit();
        var clipSeq = document.clipSequence();
        var loadedTracks = clipSeq.tracks().isEmpty() ? ClipBaker.defaultTracksFor(circuit) : clipSeq.tracks();
        tracks.setAll(loadedTracks);
        clips.setAll(clipSeq.clips());
        dt.set(clipSeq.dt());
        totalDuration.set(clipSeq.totalDuration());

        selectedClipIds.clear();
        primarySelectedClipId.set(null);
        viewport.setMaxTimePreservePosition(totalDuration.get());
        viewport.fitViewportToData();
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
            clipSeq, activeSimConfigId.get(),
            activeHardwareConfigId.get()
        );
    }

    public boolean isDirty() {
        var orig = originalDocument.get();
        if (orig == null) return false;
        if (!Objects.equals(activeSimConfigId.get(), originalSimConfigId)) return true;
        if (!Objects.equals(activeHardwareConfigId.get(), orig.preferredHardwareConfigId())) return true;
        var origClipSeq = orig.clipSequence();
        return !clips.equals(origClipSeq.clips())
            || !tracks.equals(origClipSeq.tracks())
            || dt.get() != origClipSeq.dt()
            || totalDuration.get() != origClipSeq.totalDuration();
    }

    /**
     * Resolve the currently bound hardware config document via the project
     * repository. Returns {@code null} if nothing is bound, the binding points
     * to an unknown id (e.g. the config was deleted), or no repository
     * supplier is wired. The result is always fresh — never a cached reference
     * that could go stale after a rename or in-place edit.
     */
    public HardwareConfigDocument activeHardwareConfigDoc() {
        var id = activeHardwareConfigId.get();
        if (id == null) return null;
        var repo = repositorySupplier.get();
        if (repo == null) return null;
        return repo.hardwareConfig(id);
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

    /** Resolve a specific coil's {@link EigenfieldDocument}, or {@code null} if unknown. */
    public EigenfieldDocument eigenfieldFor(CircuitComponent.Coil coil) {
        if (coil == null || coil.eigenfieldId() == null) return null;
        var repo = repositorySupplier.get();
        if (repo == null) return null;
        return repo.node(coil.eigenfieldId()) instanceof EigenfieldDocument ef ? ef : null;
    }

    /** Resolve the eigenfield document reachable from a channel's source (through switches), or {@code null}. */
    public EigenfieldDocument eigenfieldForChannel(SequenceChannel channel) {
        var src = sourceForChannel(channel);
        if (src == null || src.isGate()) return null;
        var circuit = activeCircuit();
        if (circuit == null) return null;
        var coil = CircuitGraph.coilReachableFrom(circuit, src.id()).orElse(null);
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
        var track = new Track(outputChannel, name != null ? name : defaultTrackNameFor(outputChannel));
        mutate(() -> tracks.add(track));
        return track;
    }

    /** Remove a track and all its clips. */
    public void removeTrack(String trackId) {
        int idx = indexOfTrack(trackId);
        if (idx < 0) return;
        mutate(() -> {
            tracks.remove(idx);
            clips.removeIf(c -> trackId.equals(c.trackId()));
            selectedClipIds.removeIf(id -> findClip(id) == null);
            if (primarySelectedClipId.get() != null && findClip(primarySelectedClipId.get()) == null) {
                primarySelectedClipId.set(selectedClipIds.isEmpty() ? null : selectedClipIds.iterator().next());
            }
        });
    }

    /**
     * Move a track to a different position in the lane stack. {@code newIndex}
     * is interpreted as the destination index <em>after</em> removal — passing
     * the track's current index is a no-op.
     */
    public void reorderTrack(String trackId, int newIndex) {
        int from = indexOfTrack(trackId);
        if (from < 0) return;
        int to = MathUtil.clamp(newIndex, 0, tracks.size() - 1);
        if (from == to) return;
        mutate(() -> {
            var moved = tracks.remove(from);
            tracks.add(to, moved);
        });
    }

    public void renameTrack(String trackId, String newName) {
        int idx = indexOfTrack(trackId);
        if (idx < 0) return;
        mutate(() -> tracks.set(idx, tracks.get(idx).withName(newName == null ? "" : newName)));
    }

    /** Backwards-compat alias: routes the simulator side. */
    public void setTrackOutputChannel(String trackId, SequenceChannel newChannel) {
        setTrackSimChannel(trackId, newChannel);
    }

    public void setTrackSimChannel(String trackId, SequenceChannel newChannel) {
        int idx = indexOfTrack(trackId);
        if (idx < 0) return;
        mutate(() -> tracks.set(idx, tracks.get(idx).withSimChannel(newChannel)));
    }

    public void setTrackHardwareChannel(String trackId, SequenceChannel newChannel) {
        int idx = indexOfTrack(trackId);
        if (idx < 0) return;
        mutate(() -> tracks.set(idx, tracks.get(idx).withHardwareChannel(newChannel)));
    }

    /**
     * List of addressable output channels for a hardware config — pulled from
     * the bound plugin's capabilities. Returns an empty list when no hardware
     * config is bound or the plugin can't be resolved.
     */
    public List<SequenceChannel> availableHardwareChannels(HardwareConfigDocument hwConfig) {
        if (hwConfig == null || hwConfig.config() == null) return List.of();
        var plugin = HardwarePluginRegistry.byId(hwConfig.config().pluginId()).orElse(null);
        return plugin == null ? List.of() : plugin.capabilities().outputChannels();
    }

    public void reorderTrack(int fromIndex, int toIndex) {
        if (fromIndex < 0 || toIndex < 0 || fromIndex >= tracks.size() || toIndex >= tracks.size()) return;
        if (fromIndex == toIndex) return;
        mutate(() -> tracks.add(toIndex, tracks.remove(fromIndex)));
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
        mutate(() -> { clips.add(clip); selectOnly(clip.id()); });
    }

    public void removeClip(String clipId) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        mutate(() -> {
            clips.remove(idx);
            selectedClipIds.remove(clipId);
            if (clipId.equals(primarySelectedClipId.get())) {
                primarySelectedClipId.set(selectedClipIds.isEmpty() ? null : selectedClipIds.iterator().next());
            }
        });
    }

    public void deleteSelectedClips() {
        if (selectedClipIds.isEmpty()) return;
        var toRemove = Set.copyOf(selectedClipIds);
        mutate(() -> {
            clips.removeIf(c -> toRemove.contains(c.id()));
            selectedClipIds.clear();
            primarySelectedClipId.set(null);
        });
    }

    public void moveClip(String clipId, double newStartTime) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        double clamped = Math.max(0, newStartTime);
        mutate(() -> clips.set(idx, clips.get(idx).withStartTime(clamped)));
    }

    public void moveSelectedClips(double deltaMicros) {
        if (selectedClipIds.isEmpty()) return;
        mutate(() -> {
            for (int i = 0; i < clips.size(); i++) {
                var clip = clips.get(i);
                if (selectedClipIds.contains(clip.id())) {
                    clips.set(i, clip.withStartTime(Math.max(0, clip.startTime() + deltaMicros)));
                }
            }
        });
    }

    public void resizeClip(String clipId, double newDuration) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        var clip = clips.get(idx);
        double duration = Math.max(dt.get(), newDuration);
        double neededMedia = clip.mediaOffset() + duration;
        mutate(() -> clips.set(idx, neededMedia > clip.mediaDuration()
            ? clip.withDuration(duration).withMediaDuration(neededMedia)
            : clip.withDuration(duration)));
    }

    public void resizeClipLeft(String clipId, double newStartTime) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        var clip = clips.get(idx);
        double startTime = MathUtil.clamp(newStartTime, 0, clip.endTime() - dt.get());
        double delta = startTime - clip.startTime();
        double duration = clip.endTime() - startTime;
        double mediaOffset = clip.mediaOffset() + delta;
        double mediaDuration = clip.mediaDuration();
        if (mediaOffset < 0) { mediaDuration += -mediaOffset; mediaOffset = 0; }
        double finalMediaOffset = mediaOffset;
        double finalMediaDuration = mediaDuration;
        mutate(() -> clips.set(idx, new SignalClip(
            clip.id(), clip.trackId(), clip.shape(),
            startTime, duration, clip.amplitude(),
            finalMediaOffset, finalMediaDuration
        )));
    }

    public void setClipAmplitude(String clipId, double amplitude) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        mutate(() -> clips.set(idx, clips.get(idx).withAmplitude(amplitude)));
    }

    /** Replace a clip's shape outright (and its typed parameters). */
    public void setClipShape(String clipId, ClipShape newShape) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        mutate(() -> clips.set(idx, clips.get(idx).withShape(newShape)));
    }

    /** Morph a clip to a different shape kind, using that kind's defaults for the current media duration. */
    public void changeClipKind(String clipId, ClipKind newKind) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        mutate(() -> {
            var clip = clips.get(idx);
            clips.set(idx, clip.withShape(newKind.defaultFor(clip.mediaDuration())));
        });
    }

    /** Move a clip from one track to another (cross-track drag). */
    public void changeClipTrack(String clipId, String newTrackId) {
        int idx = indexOfClip(clipId);
        if (idx < 0 || findTrack(newTrackId) == null) return;
        mutate(() -> clips.set(idx, clips.get(idx).withTrack(newTrackId)));
    }

    public void duplicateSelectedClips() {
        var sel = selectedClips();
        if (sel.isEmpty()) return;
        mutate(() -> {
            var newIds = new LinkedHashSet<String>();
            for (var clip : sel) {
                var dupe = clip.withNewId().withStartTime(clip.startTime() + clip.duration() * 0.1);
                clips.add(dupe);
                newIds.add(dupe.id());
            }
            selectedClipIds.clear();
            selectedClipIds.addAll(newIds);
            primarySelectedClipId.set(newIds.iterator().next());
        });
    }

    public void duplicateClip(String clipId) {
        var clip = findClip(clipId);
        if (clip == null) return;
        mutate(() -> {
            var dupe = clip.withNewId().withStartTime(clip.startTime() + clip.duration() * 0.1);
            clips.add(dupe);
            selectOnly(dupe.id());
        });
    }

    public void setActiveSimConfig(ProjectNodeId configId) {
        if (Objects.equals(configId, activeSimConfigId.get())) return;
        mutate(() -> activeSimConfigId.set(configId));
    }

    public void setTotalDuration(double duration) {
        if (duration <= 0) return;
        mutate(() -> {
            totalDuration.set(duration);
            // Push the new bound into the viewport; normalize() handles
            // clamping vS/vE/tS/tE to fit if they overhung the old maxTime.
            viewport.setMaxTimePreservePosition(duration);
        });
    }

    public void recentreClip(String clipId) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        mutate(() -> clips.set(idx, clips.get(idx).centred()));
    }

    public void replaceClip(String clipId, SignalClip replacement) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        mutate(() -> clips.set(idx, replacement));
    }

    // ── Spline points ────────────────────────────────────────────────────────

    public void updateSplinePoint(String clipId, int pointIndex, ClipShape.Spline.Point newPoint) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        var clip = clips.get(idx);
        if (!(clip.shape() instanceof ClipShape.Spline spline)) return;
        if (pointIndex < 0 || pointIndex >= spline.points().size()) return;
        mutate(() -> {
            var points = new ArrayList<>(spline.points());
            points.set(pointIndex, newPoint);
            clips.set(idx, clip.withShape(spline.withPoints(points)));
        });
    }

    public void addSplinePoint(String clipId, ClipShape.Spline.Point point) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        var clip = clips.get(idx);
        if (!(clip.shape() instanceof ClipShape.Spline spline)) return;
        mutate(() -> {
            var points = new ArrayList<>(spline.points());
            int insertAt = 0;
            while (insertAt < points.size() && points.get(insertAt).t() < point.t()) insertAt++;
            points.add(insertAt, point);
            clips.set(idx, clip.withShape(spline.withPoints(points)));
        });
    }

    public void removeSplinePoint(String clipId, int pointIndex) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        var clip = clips.get(idx);
        if (!(clip.shape() instanceof ClipShape.Spline spline)) return;
        if (pointIndex < 0 || pointIndex >= spline.points().size()) return;
        if (spline.points().size() <= 2) return;
        mutate(() -> {
            var points = new ArrayList<>(spline.points());
            points.remove(pointIndex);
            clips.set(idx, clip.withShape(spline.withPoints(points)));
        });
    }

    // ── Split ────────────────────────────────────────────────────────────────

    public void splitClip(String clipId, double splitTime) {
        int idx = indexOfClip(clipId);
        if (idx < 0) return;
        var clip = clips.get(idx);
        if (splitTime <= clip.startTime() || splitTime >= clip.endTime()) return;
        mutate(() -> {
            var split = clip.split(splitTime);
            clips.set(idx, split.left());
            clips.add(idx + 1, split.right());
            selectedClipIds.clear();
            selectedClipIds.add(split.left().id());
            selectedClipIds.add(split.right().id());
            primarySelectedClipId.set(split.left().id());
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Snapping
    // ══════════════════════════════════════════════════════════════════════════

    public double snapTime(double rawTime) {
        if (!snapEnabled.get()) return rawTime;
        double viewSpan = viewport.vE.get() - viewport.vS.get();
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
    // Clip creation factories
    // ══════════════════════════════════════════════════════════════════════════

    /** Create a clip with a duration equal to 15 % of the visible span. */
    public SignalClip createDefaultClip(ClipKind kind, String trackId, double startTime) {
        double clipDuration = Math.max(dt.get() * 5, (viewport.vE.get() - viewport.vS.get()) * 0.15);
        var track = findTrack(trackId);
        return SignalClip.freshCentred(trackId, kind, startTime, clipDuration,
            track != null ? defaultAmplitudeForChannel(track.simChannel()) : 1.0);
    }

    /** Create a clip with an explicit duration (used by click-and-drag creation). */
    public SignalClip createClipCentred(ClipKind kind, String trackId, double startTime, double duration) {
        var track = findTrack(trackId);
        return SignalClip.freshCentred(trackId, kind, startTime, duration,
            track != null ? defaultAmplitudeForChannel(track.simChannel()) : 1.0);
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

    /** Run an undoable mutation: snapshot for undo, apply the change, bump the revision. */
    private void mutate(Runnable change) {
        pushUndo();
        change.run();
        bumpRevision();
    }

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

}
