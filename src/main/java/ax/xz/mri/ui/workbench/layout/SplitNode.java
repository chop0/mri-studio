package ax.xz.mri.ui.workbench.layout;

import javafx.geometry.Orientation;

/** Split-pane layout node. */
public record SplitNode(
    Orientation orientation,
    double dividerPosition,
    DockNode first,
    DockNode second
) implements DockNode {
}
