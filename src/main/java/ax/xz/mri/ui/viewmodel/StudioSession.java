package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.ui.model.IsochromatCollectionModel;
import ax.xz.mri.ui.model.IsochromatSelectionModel;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.List;

/** Composition root for the new workbench-facing UI view models and services. */
public class StudioSession {
    public final DocumentSessionViewModel document = new DocumentSessionViewModel();
    public final ProjectSessionViewModel project = new ProjectSessionViewModel();
    public final ViewportViewModel viewport = new ViewportViewModel();
    public final SphereViewModel sphere = new SphereViewModel();
    public final GeometryViewModel geometry = new GeometryViewModel();
    public final MagnetisationColouringViewModel colouring = new MagnetisationColouringViewModel();
    public final TimelineViewModel timeline = new TimelineViewModel(viewport);
    public final DockingViewModel docking = new DockingViewModel();
    public final IsochromatSelectionModel selection = new IsochromatSelectionModel();
    public final IsochromatCollectionModel points = new IsochromatCollectionModel(selection);
    public final PointsViewModel pointsView = new PointsViewModel(points, selection);
    public final DerivedComputationViewModel derived = new DerivedComputationViewModel();
    public final GeometryShadingService geometryShading = new GeometryShadingService();
    public final ReferenceFrameViewModel reference = new ReferenceFrameViewModel();
    public final HeatMapViewModel phaseMapZ = new HeatMapViewModel("\u03c6(z, t)", new double[]{-6, -3, 0, 3, 6}, true);
    public final HeatMapViewModel phaseMapR = new HeatMapViewModel("\u03c6(r, t)", new double[]{0, 10, 20, 30}, false);
    public final TracePlotViewModel tracePhase =
        new TracePlotViewModel("Phase \u03c6", "\u00b0", -180, 180, new double[]{-180, -90, 0, 90, 180}, TracePlotViewModel.PlotKind.PHASE);
    public final TracePlotViewModel tracePolar =
        new TracePlotViewModel("Polar \u03b8", "\u00b0", 0, 180, new double[]{0, 45, 90, 135, 180}, TracePlotViewModel.PlotKind.POLAR);
    public final TracePlotViewModel traceMagnitude =
        new TracePlotViewModel("|M\u22a5|", "", 0, 1.08, new double[]{0, 0.25, 0.5, 0.75, 1}, TracePlotViewModel.PlotKind.MPERP);
    public final MessagesViewModel messages = new MessagesViewModel();

    /** The active sequence editing session, or null when not editing a sequence. */
    public final ObjectProperty<SequenceEditSession> activeEditSession = new SimpleObjectProperty<>(null);

    public StudioSession() {
        derived.setErrorSink(ex -> messages.logError("DerivedComputation", ex.getMessage(), ex));
        points.setErrorSink(ex -> messages.logError("Isochromats", ex.getMessage(), ex));

        viewport.tC.addListener((obs, oldValue, newValue) -> refreshGeometryShading());
        reference.enabled.addListener((obs, oldValue, newValue) -> {
            refreshReferenceFrame();
            refreshGeometryShading();
        });
        reference.r.addListener((obs, oldValue, newValue) -> {
            refreshReferenceFrame();
            refreshGeometryShading();
        });
        reference.z.addListener((obs, oldValue, newValue) -> {
            refreshReferenceFrame();
            refreshGeometryShading();
        });

        points.resetToDefaults();
    }

    /**
     * The single entry point for feeding data to all analysis panes.
     * Called by both the simulation session (primary) and the import path.
     *
     * <p>Sets all analysis state in one shot — no listener cascades, no generation races.
     * The order is carefully chosen: context first, then computation triggers.
     */
    public void loadSimulationResult(BlochData data, List<PulseSegment> pulse) {
        document.blochData.set(data);
        document.currentPulse.set(pulse);
        updateViewportBoundsPreservePosition(data);
        points.setContext(data, pulse);
        points.resimulateAll();
        derived.recompute(data, pulse);
        refreshReferenceFrame();
        refreshGeometryShading();
    }

    /** Capture the full tool window state for the current document. */
    public DocumentSnapshot captureToolSnapshot() {
        return new DocumentSnapshot(
            viewport.captureSnapshot(),
            sphere.captureSnapshot(),
            geometry.zCenter.get(),
            geometry.halfHeight.get(),
            reference.enabled.get(),
            reference.r.get(),
            reference.z.get(),
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
        // Viewport + sphere
        viewport.restoreSnapshot(snap.viewport());
        sphere.restoreSnapshot(snap.sphere());
        // Geometry
        geometry.zCenter.set(snap.geoZCenter());
        geometry.halfHeight.set(snap.geoHalfHeight());
        // Reference frame
        reference.enabled.set(snap.refEnabled());
        reference.r.set(snap.refR());
        reference.z.set(snap.refZ());
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
     * Push data to analysis panes for a tab switch without resetting tool state.
     * Unlike loadSimulationResult, this does NOT reset points to defaults or recompute
     * viewport bounds — the caller restores those from the document snapshot.
     */
    public void pushDataForTabSwitch(BlochData data, java.util.List<PulseSegment> pulse) {
        document.blochData.set(data);
        document.currentPulse.set(pulse);
        if (data != null && pulse != null) {
            points.setContext(data, pulse);
            points.resimulateAll();
            derived.recompute(data, pulse);
            refreshReferenceFrame();
            refreshGeometryShading();
        }
    }

    public void dispose() {
        derived.dispose();
        geometryShading.dispose();
        points.dispose();
        reference.dispose();
    }

    private void updateViewportBoundsPreservePosition(BlochData data) {
        if (data == null || data.field() == null || data.field().segments == null) {
            viewport.setMaxTimePreservePosition(1000);
            return;
        }
        double total = data.field().segments.stream()
            .mapToDouble(segment -> segment.durationMicros())
            .sum();
        viewport.setMaxTimePreservePosition(total);
    }

    private void refreshGeometryShading() {
        geometryShading.request(geometry, document.blochData.get(), document.currentPulse.get(), viewport.tC.get(), reference);
    }

    private void refreshReferenceFrame() {
        reference.refresh(document.blochData.get(), document.currentPulse.get());
    }
}
