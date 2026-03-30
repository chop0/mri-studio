package ax.xz.mri.ui.viewmodel;

import java.util.List;

/** Precomputed shading cells for the geometry view at one cursor position. */
public record GeometryShadingSnapshot(
    List<Double> zSamples,
    CellSample[][] cells,
    boolean signalModeBlocked
) {
    public record CellSample(double phaseDeg, double brightness) {}
}
