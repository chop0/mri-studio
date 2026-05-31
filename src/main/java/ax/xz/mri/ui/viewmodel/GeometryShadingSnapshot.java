package ax.xz.mri.ui.viewmodel;

import java.util.List;

/**
 * Precomputed shading cells for the geometry view at one cursor position.
 *
 * <p>Cells live on a 2-D grid spanning the active {@link SlicePlane} in its
 * own {@code (u, v)} basis. The renderer reads {@link #uMetres} for the
 * horizontal axis and {@link #vMetres} for the vertical axis; the underlying
 * world position of cell {@code (i, j)} is
 * {@code plane.sampleAt(uMetres[i], vMetres[j])}.
 *
 * <p>{@link #plane} is kept on the snapshot so the renderer can rotate the
 * cell back into world coordinates for hit-tests, hover-readouts, and
 * snap-to-NV-centre overlays.
 */
public record GeometryShadingSnapshot(
    SlicePlane plane,
    List<Double> uMetres,
    List<Double> vMetres,
    CellSample[][] cells
) {
    public record CellSample(double phaseDeg, double mPerp, double signalProjection) {}
}
