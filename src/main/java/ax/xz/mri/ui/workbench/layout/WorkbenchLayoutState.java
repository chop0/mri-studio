package ax.xz.mri.ui.workbench.layout;

import java.util.List;

/** Persisted workbench layout: dock tree plus floating window placements. */
public record WorkbenchLayoutState(
    DockNode dockRoot,
    List<FloatingWindowState> floatingWindows
) {
}
