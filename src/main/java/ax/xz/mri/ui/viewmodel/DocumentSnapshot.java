package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.simulation.Trajectory;
import ax.xz.mri.model.simulation.Vec3;
import ax.xz.mri.ui.model.IsochromatEntry;
import ax.xz.mri.ui.model.IsochromatId;
import ax.xz.mri.ui.time.TimeAxis;

import java.util.List;
import java.util.Set;

/**
 * Complete snapshot of global tool window state, saved per-document tab.
 * Captured on tab blur, restored on tab focus, so switching between documents
 * feels like each document owns its own analysis views.
 */
public record DocumentSnapshot(
    TimeAxis.Snapshot timeAxis,
    SphereViewModel.SphereSnapshot sphere,
    double geoZCenter,
    double geoHalfHeight,
    boolean refEnabled,
    Vec3 refPosition,
    Trajectory refTrajectory,
    List<IsochromatEntry> points,
    Set<IsochromatId> selectedPointIds,
    IsochromatId primarySelectedPointId,
    MagnetisationColouringViewModel.HueSource hueSource,
    MagnetisationColouringViewModel.BrightnessSource brightnessSource
) {
    public DocumentSnapshot {
        if (refPosition == null) refPosition = Vec3.ZERO;
        points = List.copyOf(points);
        selectedPointIds = Set.copyOf(selectedPointIds);
    }
}
