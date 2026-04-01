package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.scenario.BlochData;
import ax.xz.mri.ui.model.IsochromatCollectionModel;
import ax.xz.mri.ui.model.IsochromatSelectionModel;

/** Composition root for the new workbench-facing UI view models and services. */
public class StudioSession {
    public final DocumentSessionViewModel document = new DocumentSessionViewModel();
    public final ViewportViewModel viewport = new ViewportViewModel();
    public final SphereViewModel sphere = new SphereViewModel();
    public final GeometryViewModel geometry = new GeometryViewModel();
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

    public StudioSession() {
        document.currentPulse.addListener((obs, oldPulse, newPulse) -> {
            var data = document.blochData.get();
            points.setContext(data, newPulse);
            points.resimulateAll();
            derived.recompute(data, newPulse);
            refreshReferenceFrame();
            refreshGeometryShading();
        });

        document.blochData.addListener((obs, oldData, newData) -> {
            updateViewportBounds(newData);
            points.setContext(newData, document.currentPulse.get());
            refreshReferenceFrame();
            refreshGeometryShading();
        });

        viewport.tC.addListener((obs, oldValue, newValue) -> refreshGeometryShading());
        geometry.shadeMode.addListener((obs, oldMode, newMode) -> refreshGeometryShading());
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
    }

    public void setDocument(java.io.File file, BlochData data) {
        document.setDocument(file, data);
        points.setContext(data, document.currentPulse.get());
        points.resetToDefaults();
        updateViewportBounds(data);
        refreshReferenceFrame();
        refreshGeometryShading();
    }

    public void dispose() {
        derived.dispose();
        geometryShading.dispose();
        points.dispose();
        reference.dispose();
    }

    private void updateViewportBounds(BlochData data) {
        if (data == null || data.field() == null || data.field().segments == null) {
            viewport.maxTime.set(1000);
            viewport.resetToFullRange();
            geometry.fitVisibleRange(-80, 80);
            return;
        }
        double total = data.field().segments.stream()
            .mapToDouble(segment -> segment.durationMicros())
            .sum();
        viewport.maxTime.set(total);
        viewport.resetToFullRange();
        geometry.fitVisibleRange(data.field().zMm[0], data.field().zMm[data.field().zMm.length - 1]);
    }

    private void refreshGeometryShading() {
        geometryShading.request(geometry, document.blochData.get(), document.currentPulse.get(), viewport.tC.get(), reference);
    }

    private void refreshReferenceFrame() {
        reference.refresh(document.blochData.get(), document.currentPulse.get());
    }
}
