package ax.xz.mri.ui.model;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.service.simulation.compiled.CompiledSimulation;
import ax.xz.mri.ui.theme.StudioTheme;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * UI-facing collection of observation points with stable ids and
 * background trajectory re-computation.
 *
 * <p>Each point is a {@link Vec3} position in the FOV. The collection
 * is populated by the active simulation's substance kernels (an NV
 * ensemble's centres, or — when a {@link ax.xz.mri.model.substance.ContinuousMagnetisation}
 * exists — a small built-in fan of off-axis isochromats). Per-point
 * trajectories are computed via
 * {@link CompiledSimulation#singleSpinTrajectory} when a continuous
 * magnetisation is present; for NV-only sims trajectories stay null
 * and the {@link ax.xz.mri.ui.workbench.pane.SphereWorkbenchPane}
 * gates itself accordingly.
 */
public class IsochromatCollectionModel {

    /**
     * Continuous-magnetisation default fan: a few off-axis points used to
     * populate the Points pane when a Bloch substance is in the FOV.
     * Coordinates are in metres. When no Bloch substance is present this
     * default fan is ignored — the pane is populated from the substance's
     * own spin positions instead.
     */
    private static final Vec3[] DEFAULT_BLOCH_POSITIONS = {
        new Vec3(0,       0, 0),
        new Vec3(0,       0, 2e-3),
        new Vec3(0,       0, 4e-3),
        new Vec3(0,       0, 10e-3),
        new Vec3(15e-3,   0, 0),
    };

    public final ObservableList<IsochromatEntry> entries = FXCollections.observableArrayList();

    private final IsochromatSelectionModel selectionModel;
    private final Executor simulationExec;
    private final Consumer<Runnable> uiDispatcher;
    private final Runnable disposer;
    private final AtomicLong nextId = new AtomicLong(1);
    private final AtomicLong simulationGeneration = new AtomicLong();
    private Consumer<Throwable> errorSink = ex -> { };

    private CompiledSimulation currentSimulation;
    private List<PulseSegment> currentPulse;
    private int colourIndex;

    public IsochromatCollectionModel(IsochromatSelectionModel selectionModel) {
        this(selectionModel, createSimulationExecutor(), Platform::runLater, null);
    }

    IsochromatCollectionModel(
        IsochromatSelectionModel selectionModel,
        Executor simulationExec,
        Consumer<Runnable> uiDispatcher,
        Runnable disposer
    ) {
        this.selectionModel = selectionModel;
        this.simulationExec = simulationExec;
        this.uiDispatcher = uiDispatcher;
        this.disposer = disposer != null ? disposer : () -> { };
        entries.addListener((javafx.collections.ListChangeListener<IsochromatEntry>) change ->
            selectionModel.removeMissing(entries.stream().map(IsochromatEntry::id).toList()));
    }

    public void setErrorSink(Consumer<Throwable> sink) {
        this.errorSink = sink != null ? sink : ex -> { };
    }

    public void setContext(CompiledSimulation simulation, List<PulseSegment> pulse) {
        var previousSimulation = currentSimulation;
        currentSimulation = simulation;
        currentPulse = pulse;
        simulationGeneration.incrementAndGet();
        // Substance-aware seeding. The StudioSession is shared across document
        // tabs, so when the user switches from a Bloch sim to an NV sim the
        // entries list still holds the previous sim's SCENARIO_DEFAULT or
        // NV_CENTRE entries — meaningless for the new sim. Replace those when
        // the substance kind changes; preserve USER entries (the user's own
        // probes are FOV-positions and survive substance switches).
        if (simulation == null) return;
        if (entries.isEmpty() || substanceKindChanged(previousSimulation, simulation)) {
            replaceProvidedDefaults();
        }
    }

    /** Did the dominant substance kind change between two compiled sims? */
    private static boolean substanceKindChanged(CompiledSimulation oldSim, CompiledSimulation newSim) {
        if (oldSim == null) return true;
        return primarySubstanceKindOf(oldSim) != primarySubstanceKindOf(newSim);
    }

    private enum SubstanceKind { CONTINUOUS, NV, EMPTY }

    private static SubstanceKind primarySubstanceKindOf(CompiledSimulation sim) {
        if (sim == null) return SubstanceKind.EMPTY;
        for (var s : sim.substances()) {
            if (s instanceof ax.xz.mri.model.substance.ContinuousMagnetisation) return SubstanceKind.CONTINUOUS;
        }
        for (var s : sim.substances()) {
            if (s instanceof ax.xz.mri.model.substance.NvEnsemble) return SubstanceKind.NV;
        }
        return SubstanceKind.EMPTY;
    }

    /**
     * Replace SCENARIO_DEFAULT and NV_CENTRE entries with fresh ones drawn
     * from the current simulation's substance; keep USER entries untouched.
     */
    private void replaceProvidedDefaults() {
        var userPoints = entries.stream()
            .filter(entry -> entry.origin() == IsochromatOrigin.USER)
            .toList();
        colourIndex = userPoints.size();
        selectionModel.clear();
        var fresh = new ArrayList<IsochromatEntry>(userPoints);
        switch (primarySubstanceKindOf(currentSimulation)) {
            case CONTINUOUS -> {
                for (int i = 0; i < DEFAULT_BLOCH_POSITIONS.length; i++) {
                    var p = DEFAULT_BLOCH_POSITIONS[i];
                    fresh.add(new IsochromatEntry(
                        nextIsoId(), p, nextColour(), true,
                        i == 0 ? "Centre" : String.format("(%.2f, %.2f, %.2f) mm",
                            p.x() * 1e3, p.y() * 1e3, p.z() * 1e3),
                        IsochromatOrigin.SCENARIO_DEFAULT, false, null));
                }
            }
            case NV -> {
                int idx = 0;
                for (var p : pickNvCentrePositions(currentSimulation)) {
                    idx++;
                    fresh.add(new IsochromatEntry(
                        nextIsoId(), p, nextColour(), true,
                        "NV " + idx,
                        IsochromatOrigin.NV_CENTRE, true, null));
                }
            }
            case EMPTY -> { /* leave fresh = just USER */ }
        }
        entries.setAll(fresh);
        resimulateAll();
    }

    public Optional<IsochromatEntry> findById(IsochromatId id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    /**
     * Populate the entry list from the active simulation. When a continuous
     * magnetisation substance is in the FOV the built-in Bloch fan is used;
     * otherwise the points come from the first NV substance's centres (so
     * the user immediately sees the NV array in the points pane).
     *
     * <p>NV centre entries are tagged {@link IsochromatOrigin#NV_CENTRE} and
     * locked — the Points pane refuses to move or delete them. They mirror
     * the substance editor's centre list; mutate that list to change them.
     */
    public void resetToDefaults() {
        // Drop all entries (including user-added probes — this is the
        // "Reset Defaults" verb, not the substance-switch verb) and
        // repopulate from the active substance.
        colourIndex = 0;
        selectionModel.clear();
        entries.clear();
        if (currentSimulation == null) return;
        replaceProvidedDefaults();
    }

    public void clearUserPoints() {
        var retained = entries.stream()
            .filter(entry -> entry.origin() == IsochromatOrigin.SCENARIO_DEFAULT)
            .toList();
        entries.setAll(retained);
    }

    public void addUserPoint(Vec3 position, String name) {
        var entry = new IsochromatEntry(
            nextIsoId(),
            position,
            nextColour(),
            true,
            name,
            IsochromatOrigin.USER,
            false,
            null
        );
        entries.add(entry);
        selectionModel.setSingle(entry.id());
        resimulateIds(List.of(entry.id()));
    }

    public void duplicateSelected() {
        var created = new ArrayList<IsochromatEntry>();
        for (var selectedId : selectionModel.selectedIds) {
            findById(selectedId).ifPresent(entry -> {
                var shifted = entry.position().plus(new Vec3(0.5e-3, 0, 0.5e-3));
                created.add(new IsochromatEntry(
                    nextIsoId(),
                    shifted,
                    entry.colour(),
                    entry.visible(),
                    entry.name() + " copy",
                    IsochromatOrigin.USER,
                    entry.locked(),
                    null
                ));
            });
        }
        if (!created.isEmpty()) {
            entries.addAll(created);
            selectionModel.setAll(created.stream().map(IsochromatEntry::id).toList());
            resimulateIds(created.stream().map(IsochromatEntry::id).toList());
        }
    }

    public void remove(Collection<IsochromatId> ids) {
        if (ids.isEmpty()) return;
        // NV centre entries mirror the substance editor's centre list — deleting
        // here would diverge the Points pane from the substance. The substance
        // editor is the source of truth.
        entries.removeIf(entry -> ids.contains(entry.id())
            && entry.origin() != IsochromatOrigin.NV_CENTRE);
    }

    public void remove(IsochromatId id) { remove(List.of(id)); }

    public void rename(IsochromatId id, String name) {
        replaceEntry(id, entry -> entry.withName(name));
    }

    public void recolor(IsochromatId id, Color colour) {
        replaceEntry(id, entry -> entry.withColour(colour));
    }

    public void toggleVisibility(IsochromatId id) {
        replaceEntry(id, entry -> entry.withVisible(!entry.visible()));
    }

    public void setLocked(IsochromatId id, boolean locked) {
        replaceEntry(id, entry -> entry.withLocked(locked));
    }

    public void move(IsochromatId id, Vec3 position) {
        var entry = findById(id).orElse(null);
        if (entry == null) return;
        // NV centres are anchored to the substance editor's centre list —
        // the cross-section / 3-D viewport can attempt to drag them, but the
        // model refuses. Edit them in the substance editor instead.
        if (entry.origin() == IsochromatOrigin.NV_CENTRE) return;
        replaceEntry(id, e -> e.withPosition(position));
        resimulateIds(List.of(id));
    }

    public void resimulateAll() {
        resimulateIds(entries.stream().map(IsochromatEntry::id).toList());
    }

    public void dispose() { disposer.run(); }

    private void resimulateIds(Collection<IsochromatId> ids) {
        if (currentSimulation == null || currentPulse == null || ids.isEmpty()) return;
        long generation = simulationGeneration.incrementAndGet();
        var snapshot = entries.stream()
            .filter(entry -> ids.contains(entry.id()))
            .toList();
        var simulation = currentSimulation;
        simulationExec.execute(() -> {
            try {
                var results = new ArrayList<IsochromatEntry>(snapshot.size());
                for (var entry : snapshot) {
                    if (Thread.currentThread().isInterrupted()) return;
                    var trajectory = simulation.singleSpinTrajectory(entry.position());
                    results.add(entry.withTrajectory(trajectory));
                }
                uiDispatcher.accept(() -> {
                    if (generation != simulationGeneration.get()) return;
                    for (var result : results) replaceExisting(result);
                });
            } catch (Exception ex) {
                errorSink.accept(ex);
            }
        });
    }

    /** Lab-frame positions of every NV centre in the first NV substance. */
    private static List<Vec3> pickNvCentrePositions(CompiledSimulation sim) {
        for (var s : sim.substances()) {
            if (s instanceof ax.xz.mri.model.substance.NvEnsemble nv) {
                var out = new ArrayList<Vec3>();
                for (var c : nv.centres()) {
                    out.add(new Vec3(c.xMetres(), c.yMetres(), c.zMetres()));
                }
                return out;
            }
        }
        return List.of();
    }

    private void replaceExisting(IsochromatEntry updated) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).id().equals(updated.id())) {
                entries.set(index, updated);
                return;
            }
        }
    }

    private void replaceEntry(IsochromatId id, java.util.function.UnaryOperator<IsochromatEntry> updater) {
        for (int index = 0; index < entries.size(); index++) {
            var current = entries.get(index);
            if (current.id().equals(id)) {
                entries.set(index, updater.apply(current));
                return;
            }
        }
    }

    private IsochromatId nextIsoId() {
        return new IsochromatId(nextId.getAndIncrement());
    }

    private Color nextColour() {
        var colours = StudioTheme.ISOCHROMAT_COLOURS;
        return colours[colourIndex++ % colours.length];
    }

    private static ExecutorService createSimulationExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "isochromat-sim");
            thread.setDaemon(true);
            return thread;
        });
    }
}
