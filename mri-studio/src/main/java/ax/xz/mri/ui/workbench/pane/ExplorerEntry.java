package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.ui.workbench.StudioIconKind;

/** Lightweight tree-row model for the semantic project explorer. */
public record ExplorerEntry(
    String label,
    ProjectNodeId nodeId,
    StudioIconKind iconKind,
    boolean synthetic
) {
}
