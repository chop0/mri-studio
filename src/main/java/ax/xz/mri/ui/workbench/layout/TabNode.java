package ax.xz.mri.ui.workbench.layout;

import java.util.List;

/** Tabbed layout node. */
public record TabNode(
    List<DockNode> tabs,
    int selectedIndex
) implements DockNode {
}
