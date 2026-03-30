package ax.xz.mri.ui.workbench.layout;

import ax.xz.mri.ui.workbench.PaneId;

/** Leaf dock node referencing one first-class pane. */
public record PaneLeaf(PaneId paneId) implements DockNode {
}
