package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.simulation.Trajectory;
import ax.xz.mri.ui.model.IsochromatEntry;
import ax.xz.mri.ui.model.IsochromatId;

import java.util.List;
import java.util.Set;

/**
 * Complete snapshot of global tool window state, saved per-document tab.
 * Captured on tab blur, restored on tab focus, so switching between documents
 * feels like each document owns its own analysis views.
 */
public record DocumentSnapshot(
    ViewportViewModel.ViewportSnapshot viewport,
    SphereViewModel.SphereSnapshot sphere,
    double geoZCenter,
    double geoHalfHeight,
    boolean refEnabled,
    double refR,
    double refZ,
    Trajectory refTrajectory,
    List<IsochromatEntry> points,
    Set<IsochromatId> selectedPointIds,
    IsochromatId primarySelectedPointId,
    MagnetisationColouringViewModel.HueSource hueSource,
    MagnetisationColouringViewModel.BrightnessSource brightnessSource
) {
    public DocumentSnapshot {
        points = List.copyOf(points);
        selectedPointIds = Set.copyOf(selectedPointIds);
    }
}
