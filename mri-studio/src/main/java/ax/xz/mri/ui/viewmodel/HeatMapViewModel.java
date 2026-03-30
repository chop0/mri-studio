package ax.xz.mri.ui.viewmodel;

/** Immutable configuration for one phase-map pane. */
public record HeatMapViewModel(
    String title,
    double[] ticks,
    boolean showSliceBounds
) {
}
