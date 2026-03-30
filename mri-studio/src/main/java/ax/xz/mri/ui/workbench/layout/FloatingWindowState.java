package ax.xz.mri.ui.workbench.layout;

import ax.xz.mri.ui.workbench.PaneId;

/** Persisted bounds for one floated pane. */
public record FloatingWindowState(
    PaneId paneId,
    double x,
    double y,
    double width,
    double height
) {
}
