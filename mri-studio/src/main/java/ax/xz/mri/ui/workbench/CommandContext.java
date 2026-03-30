package ax.xz.mri.ui.workbench;

import ax.xz.mri.ui.viewmodel.StudioSession;

/** Command context passed to pane-local contributors. */
public record CommandContext(
    StudioSession session,
    PaneId paneId
) {
}
