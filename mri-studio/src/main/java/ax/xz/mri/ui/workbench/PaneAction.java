package ax.xz.mri.ui.workbench;

/** Executable UI action exposed from the workbench command system. */
public record PaneAction(
    CommandId id,
    String label,
    Runnable action
) {
    public void run() {
        action.run();
    }
}
