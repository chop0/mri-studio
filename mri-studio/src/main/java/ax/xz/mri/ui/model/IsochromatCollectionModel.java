package ax.xz.mri.ui.model;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.service.simulation.BlochSimulator;
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

/** UI-facing collection of isochromats with stable ids and background resimulation. */
public class IsochromatCollectionModel {
    private static final double[][] DEFAULT_POSITIONS = {
        {0, 0}, {0, 2}, {0, 4}, {0, 10}, {15, 0},
    };

    public final ObservableList<IsochromatEntry> entries = FXCollections.observableArrayList();

    private final IsochromatSelectionModel selectionModel;
    private final Executor simulationExec;
    private final Consumer<Runnable> uiDispatcher;
    private final Runnable disposer;
    private final AtomicLong nextId = new AtomicLong(1);
    private final AtomicLong simulationGeneration = new AtomicLong();

    private BlochData currentData;
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

    public void setContext(BlochData data, List<PulseSegment> pulse) {
        currentData = data;
        currentPulse = pulse;
        simulationGeneration.incrementAndGet();
    }

    public Optional<IsochromatEntry> findById(IsochromatId id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    public void resetToDefaults() {
        colourIndex = 0;
        selectionModel.clear();
        if (currentData == null) {
            entries.clear();
            return;
        }

        var defaults = new ArrayList<IsochromatEntry>();
        var defs = currentData.iso();
        if (defs == null || defs.isEmpty()) {
            defaults.add(new IsochromatEntry(
                nextIsoId(),
                0,
                0,
                nextColour(),
                true,
                "Centre",
                true,
                IsochromatOrigin.SCENARIO_DEFAULT,
                false,
                null
            ));
        } else {
            for (int i = 0; i < defs.size(); i++) {
                var def = defs.get(i);
                Color colour;
                try {
                    colour = Color.web(def.colour());
                } catch (Exception ignored) {
                    colour = nextColour();
                }
                double r = i < DEFAULT_POSITIONS.length ? DEFAULT_POSITIONS[i][0] : 0;
                double z = i < DEFAULT_POSITIONS.length ? DEFAULT_POSITIONS[i][1] : 0;
                defaults.add(new IsochromatEntry(
                    nextIsoId(),
                    r,
                    z,
                    colour,
                    def.inSlice(),
                    def.name(),
                    def.inSlice(),
                    IsochromatOrigin.SCENARIO_DEFAULT,
                    false,
                    null
                ));
            }
        }

        entries.setAll(defaults);
        resimulateAll();
    }

    public void clearUserPoints() {
        var retained = entries.stream()
            .filter(entry -> entry.origin() == IsochromatOrigin.SCENARIO_DEFAULT)
            .toList();
        entries.setAll(retained);
    }

    public void addUserPoint(double r, double z, String name) {
        var entry = new IsochromatEntry(
            nextIsoId(),
            r,
            z,
            nextColour(),
            true,
            name,
            Math.abs(z) <= sliceHalfMm(),
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
            findById(selectedId).ifPresent(entry -> created.add(new IsochromatEntry(
                nextIsoId(),
                entry.r() + 0.5,
                entry.z() + 0.5,
                entry.colour(),
                entry.visible(),
                entry.name() + " copy",
                isInSlice(entry.z() + 0.5),
                IsochromatOrigin.USER,
                entry.locked(),
                null
            )));
        }
        if (!created.isEmpty()) {
            entries.addAll(created);
            selectionModel.setAll(created.stream().map(IsochromatEntry::id).toList());
            resimulateIds(created.stream().map(IsochromatEntry::id).toList());
        }
    }

    public void remove(Collection<IsochromatId> ids) {
        if (ids.isEmpty()) return;
        entries.removeIf(entry -> ids.contains(entry.id()));
    }

    public void remove(IsochromatId id) {
        remove(List.of(id));
    }

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

    public void move(IsochromatId id, double r, double z) {
        replaceEntry(id, entry -> entry.withPosition(Math.max(0, r), z, isInSlice(z)));
        resimulateIds(List.of(id));
    }

    public void resimulateAll() {
        resimulateIds(entries.stream().map(IsochromatEntry::id).toList());
    }

    public void dispose() {
        disposer.run();
    }

    private void resimulateIds(Collection<IsochromatId> ids) {
        if (currentData == null || currentPulse == null || ids.isEmpty()) return;
        long generation = simulationGeneration.incrementAndGet();
        var snapshot = entries.stream()
            .filter(entry -> ids.contains(entry.id()))
            .toList();
        simulationExec.execute(() -> {
            var results = new ArrayList<IsochromatEntry>(snapshot.size());
            for (var entry : snapshot) {
                if (Thread.currentThread().isInterrupted()) return;
                var trajectory = BlochSimulator.simulate(currentData, entry.r(), entry.z(), currentPulse);
                results.add(entry.withTrajectory(trajectory));
            }
            uiDispatcher.accept(() -> {
                if (generation != simulationGeneration.get()) return;
                for (var result : results) {
                    replaceExisting(result);
                }
            });
        });
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

    private double sliceHalfMm() {
        if (currentData == null || currentData.field() == null) return 5.0;
        return (currentData.field().sliceHalf != null ? currentData.field().sliceHalf : 0.005) * 1e3;
    }

    private boolean isInSlice(double z) {
        return Math.abs(z) <= sliceHalfMm();
    }

    private static ExecutorService createSimulationExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "isochromat-sim");
            thread.setDaemon(true);
            return thread;
        });
    }
}
