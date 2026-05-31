package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.scenario.RunResult;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.SequenceBakery;
import ax.xz.mri.model.simulation.SignalTrace;
import ax.xz.mri.service.simulation.compiled.CompiledSimulation;
import ax.xz.mri.state.Autosaver;
import ax.xz.mri.state.ProjectState;
import ax.xz.mri.state.ProjectStateIO;
import ax.xz.mri.state.RecordSurgery;
import ax.xz.mri.state.UnifiedStateManager;
import ax.xz.mri.ui.model.IsochromatCollectionModel;
import ax.xz.mri.ui.model.IsochromatSelectionModel;
import ax.xz.mri.ui.edit.EditSession;
import ax.xz.mri.ui.sim.SimDispatcher;
import ax.xz.mri.ui.sim.SimRunner;
import ax.xz.mri.ui.time.TimeAxis;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.List;

/** Composition root for the new workbench-facing UI view models and services. */
public class StudioSession {
    public final DocumentSessionViewModel document = new DocumentSessionViewModel();
    /**
     * The unified state manager — single mutation point for all persistent
     * project state. Owns the in-memory {@link ProjectState} record, the undo
     * log, and the debounced autosave executor. Editors call
     * {@code state.dispatch(...)} or use a {@code DocumentEditor<T>} to mutate
     * project state.
     */
    public final UnifiedStateManager state;
    public final ProjectSessionViewModel project;
    public final ProjectStateIO projectIO = new ProjectStateIO();
    /** Memoised baker for {@code (clipSequence, circuit) → segments + pulse}. */
    public final SequenceBakery bakery = new SequenceBakery();
    public final TimeAxis timeAxis = new TimeAxis();
    public final SphereViewModel sphere = new SphereViewModel();
    public final GeometryViewModel geometry = new GeometryViewModel();
    public final MagnetisationColouringViewModel colouring = new MagnetisationColouringViewModel();
    public final DockingViewModel docking = new DockingViewModel();
    public final IsochromatSelectionModel selection = new IsochromatSelectionModel();
    public final IsochromatCollectionModel points = new IsochromatCollectionModel(selection);
    public final PointsViewModel pointsView = new PointsViewModel(points, selection);
    public final DerivedComputationViewModel derived = new DerivedComputationViewModel();
    public final GeometryShadingService geometryShading = new GeometryShadingService();
    public final ReferenceFrameViewModel reference = new ReferenceFrameViewModel();
    public final TracePlotViewModel tracePhase =
        new TracePlotViewModel("Phase \u03c6", "\u00b0", -180, 180, new double[]{-180, -90, 0, 90, 180}, TracePlotViewModel.PlotKind.PHASE);
    public final TracePlotViewModel tracePolar =
        new TracePlotViewModel("Polar \u03b8", "\u00b0", 0, 180, new double[]{0, 45, 90, 135, 180}, TracePlotViewModel.PlotKind.POLAR);
    public final TracePlotViewModel traceMagnitude =
        new TracePlotViewModel("|M\u22a5|", "", 0, 1.08, new double[]{0, 0.25, 0.5, 0.75, 1}, TracePlotViewModel.PlotKind.MPERP);
    public final MessagesViewModel messages = new MessagesViewModel();

    /** The active sequence editing session, or null when not editing a sequence. */
    public final ObjectProperty<EditSession> activeEditSession = new SimpleObjectProperty<>(null);

    public StudioSession() {
        var surgery = new RecordSurgery();
        var autosaver = new Autosaver(projectIO::write,
            ex -> messages.logWarning("Project", "Auto-save failed: " + ex.getMessage()));
        this.state = new UnifiedStateManager(ProjectState.empty(), surgery, autosaver,
            fixes -> {
                if (fixes.isEmpty()) return;
                messages.logWarning("Project",
                    "Cleared " + fixes.size() + " dangling reference(s) — " + fixes.get(0));
            });
        this.project = new ProjectSessionViewModel(state, projectIO);

        derived.setErrorSink(ex -> messages.logError("DerivedComputation", ex.getMessage(), ex));
        points.setErrorSink(ex -> messages.logError("Isochromats", ex.getMessage(), ex));
        reference.setErrorSink(ex -> messages.logError("ReferenceFrame", ex.getMessage(), ex));
        project.setErrorSink(ex -> messages.logWarning("Project", "Auto-save failed: " + ex.getMessage()));

        timeAxis.cursor.time.addListener((obs, oldValue, newValue) -> refreshGeometryShading());
        reference.enabled.addListener((obs, oldValue, newValue) -> {
            refreshReferenceFrame();
            refreshGeometryShading();
        });
        reference.position.addListener((obs, oldValue, newValue) -> {
            refreshReferenceFrame();
            refreshGeometryShading();
        });

        points.resetToDefaults();
    }

    /**
     * The single entry point for feeding a {@link RunResult} to all analysis
     * panes — called by the simulation session, the hardware run session, and
     * the import path.
     *
     * <p>Sets all analysis state in one shot — no listener cascades, no
     * generation races. The order is carefully chosen: context first, then
     * computation triggers. For a {@link RunResult.Hardware} run we set the
     * pulse + probe traces and skip the simulator-only steps (spatial
     * isochromat resimulation, derived field computations, reference-frame
     * refresh) — those panes show a "Spatial data unavailable" placeholder.
     */
    /**
     * Sets all analysis state in one shot for a fresh run. {@code precomputedPrimary}
     * is the simulator's already-computed trace; supplying it lets
     * {@link DerivedComputationViewModel#recompute} skip a redundant full
     * grid Bloch sweep. Pass {@code null} for hardware runs and importers
     * that don't have a precomputed trace.
     */
    public void loadRunResult(RunResult result, SignalTrace precomputedPrimary) {
        document.runResult.set(result);
        if (result instanceof RunResult.Simulation sim) {
            updateViewportBoundsPreservePosition(sim.simulation());
            points.setContext(sim.simulation(), sim.pulse());
            points.resimulateAll();
            derived.recompute(sim.simulation(), sim.pulse(), precomputedPrimary);
            refreshReferenceFrame();
            refreshGeometryShading();
        } else if (result instanceof RunResult.Hardware hw) {
            updateViewportBoundsForHardware(hw.pulse());
            points.setContext(null, null);
            derived.acceptProbeTraces(hw.probeTraces());
            reference.clear();
            geometryShading.clear(geometry);
        }
    }

    /** Convenience for hardware runs / importers that don't have a precomputed trace. */
    public void loadRunResult(RunResult result) {
        loadRunResult(result, null);
    }

    /** Capture the full tool window state for the current document. */
    public DocumentSnapshot captureToolSnapshot() {
        return new DocumentSnapshot(
            timeAxis.snapshot(),
            sphere.captureSnapshot(),
            geometry.zCenter.get(),
            geometry.halfHeight.get(),
            reference.enabled.get(),
            reference.position.get(),
            reference.trajectory.get(),
            java.util.List.copyOf(points.entries),
            new java.util.LinkedHashSet<>(selection.selectedIds),
            selection.primarySelectedId.get(),
            colouring.hueSource.get(),
            colouring.brightnessSource.get()
        );
    }

    /** Restore tool window state from a document's saved snapshot. */
    public void restoreToolSnapshot(DocumentSnapshot snap) {
        if (snap == null) return;
        // Time axis + sphere
        timeAxis.restore(snap.timeAxis());
        sphere.restoreSnapshot(snap.sphere());
        // Geometry
        geometry.zCenter.set(snap.geoZCenter());
        geometry.halfHeight.set(snap.geoHalfHeight());
        // Reference frame
        reference.enabled.set(snap.refEnabled());
        reference.position.set(snap.refPosition());
        reference.trajectory.set(snap.refTrajectory());
        // Points + selection
        points.entries.setAll(snap.points());
        selection.selectedIds.clear();
        selection.selectedIds.addAll(snap.selectedPointIds());
        selection.primarySelectedId.set(snap.primarySelectedPointId());
        // Colouring
        colouring.hueSource.set(snap.hueSource());
        colouring.brightnessSource.set(snap.brightnessSource());
    }

    /**
     * Push a cached run result to the analysis panes for a tab switch without
     * resetting tool state. Unlike {@link #loadRunResult(RunResult)}, this
     * does not recompute viewport bounds — the caller restores those from
     * the document snapshot.
     */
    public void pushResultForTabSwitch(RunResult result) {
        document.runResult.set(result);
        if (result instanceof RunResult.Simulation sim
                && sim.simulation() != null && sim.pulse() != null) {
            points.setContext(sim.simulation(), sim.pulse());
            points.resimulateAll();
            derived.recompute(sim.simulation(), sim.pulse());
            refreshReferenceFrame();
            refreshGeometryShading();
        } else if (result instanceof RunResult.Hardware hw) {
            points.setContext(null, null);
            derived.acceptProbeTraces(hw.probeTraces());
            reference.clear();
            geometryShading.clear(geometry);
        }
    }

    /**
     * Build a dispatcher for the given editor session, wired against this
     * studio's bakery, project repo, messages pane, time axis, and the unified
     * pane-loading entry point. The dispatcher itself is dependency-free; the
     * wiring code below is the only place that knows how to translate edit
     * state into a fresh {@link ax.xz.mri.ui.sim.SimRequest}.
     */
    public SimDispatcher newSimDispatcher(EditSession editSession) {
        var runner = new SimRunner(bakery, timeAxis.generation);
        java.util.function.Supplier<ax.xz.mri.ui.sim.SimRequest> requestSupplier = () -> {
            var config = editSession.activeConfig.get();
            var doc = editSession.toDocument();
            if (config == null || doc == null) return null;
            var configDoc = editSession.activeConfigDoc.get();
            String name = configDoc != null ? configDoc.name() : "(unnamed)";
            return new ax.xz.mri.ui.sim.SimRequest(name, doc, config, project.project(),
                timeAxis.generation.current());
        };
        var dispatcher = new SimDispatcher(requestSupplier, runner, timeAxis.generation,
            result -> {
                editSession.lastSimulationTraces.set(result.traces());
                // Pass the primary trace through so derived.recompute doesn't
                // run a second full-grid sweep when a probe trace is already
                // sitting on the SimResult.
                loadRunResult(new RunResult.Simulation(result.simulation(), result.pulse()),
                    result.traces() == null ? null : result.traces().primary());
            },
            (message, failure) -> {
                var configDoc = editSession.activeConfigDoc.get();
                String configName = configDoc != null ? configDoc.name() : "(unnamed)";
                messages.logError("Simulation",
                    "Simulation failed for config '" + configName + "': " + message, failure);
            });
        editSession.revision.addListener((obs, o, n) -> dispatcher.markDirty());
        editSession.activeConfig.addListener((obs, o, n) -> dispatcher.markDirty());
        editSession.activeConfigDoc.addListener((obs, o, n) -> {
            if (n != null) editSession.applyActiveConfig(n.config());
        });
        project.explorer.contentRevision.addListener((obs, o, n) -> {
            if (editSession.activeConfig.get() != null) dispatcher.markDirty();
        });
        return dispatcher;
    }

    public void dispose() {
        derived.dispose();
        geometryShading.dispose();
        points.dispose();
        reference.dispose();
    }

    private void updateViewportBoundsPreservePosition(CompiledSimulation simulation) {
        if (simulation == null || simulation.segments() == null) return;
        double simTotal = simulation.segments().stream()
            .mapToDouble(segment -> segment.durationMicros())
            .sum();
        // Never shrink the domain on a sim result — the sequence document's
        // totalDuration (set via EditSession.open) is authoritative for the
        // editor's domain, and a sim that simulated a sub-range mustn't
        // collapse the visible timeline to that sub-range.
        if (simTotal > timeAxis.domain.maxTime.get()) {
            timeAxis.domain.maxTime.set(simTotal);
        }
    }

    private void updateViewportBoundsForHardware(List<PulseSegment> pulse) {
        if (pulse == null || pulse.isEmpty()) return;
        int steps = pulse.stream().mapToInt(p -> p.steps().size()).sum();
        if (steps > timeAxis.domain.maxTime.get()) {
            timeAxis.domain.maxTime.set(steps);
        }
    }

    private void refreshGeometryShading() {
        geometryShading.request(geometry, document.simulation.get(), document.currentPulse.get(),
            timeAxis.cursor.time.get(), reference);
    }

    private void refreshReferenceFrame() {
        reference.refresh(document.simulation.get(), document.currentPulse.get());
    }
}
