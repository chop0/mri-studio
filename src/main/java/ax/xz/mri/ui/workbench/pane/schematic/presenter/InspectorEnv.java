package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import ax.xz.mri.project.ProjectNodeId;
import ax.xz.mri.project.ProjectRepository;
import ax.xz.mri.ui.workbench.pane.schematic.CircuitEditSession;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Ambient UI state a presenter needs to build its inspector: the editing
 * session (for mutation + readback), the project repository (for e.g. the
 * coil's eigenfield picker), and the "jump to eigenfield editor" callback
 * that a Coil's inspector exposes as an "Open" button.
 */
public record InspectorEnv(
    CircuitEditSession session,
    Supplier<ProjectRepository> repository,
    Consumer<ProjectNodeId> onJumpToEigenfield
) {}
