package ax.xz.mri.ui.workbench;

import javafx.scene.control.ContextMenu;

/** Hook for panes to add their own workbench-aware context menu actions. */
@FunctionalInterface
public interface ContextMenuContributor {
    void contribute(ContextMenu menu, CommandContext context);
}
