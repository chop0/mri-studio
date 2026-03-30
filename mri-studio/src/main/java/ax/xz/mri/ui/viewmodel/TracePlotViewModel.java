package ax.xz.mri.ui.viewmodel;

/** Immutable configuration for one trace-plot pane. */
public record TracePlotViewModel(
    String title,
    String unit,
    double min,
    double max,
    double[] ticks,
    PlotKind kind
) {
    public enum PlotKind {
        PHASE,
        POLAR,
        MPERP
    }
}
