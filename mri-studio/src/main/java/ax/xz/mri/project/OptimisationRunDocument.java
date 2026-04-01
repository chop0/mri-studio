package ax.xz.mri.project;

import java.util.List;

/** Project-owned optimisation run with capture bookmarks. */
public record OptimisationRunDocument(
    ProjectNodeId id,
    String name,
    ProjectNodeId configId,
    List<ProjectNodeId> captureIds,
    ProjectNodeId firstCaptureId,
    ProjectNodeId latestCaptureId,
    ProjectNodeId bestCaptureId,
    List<ProjectNodeId> milestoneCaptureIds
) implements ProjectNode {
    public OptimisationRunDocument {
        captureIds = List.copyOf(captureIds);
        milestoneCaptureIds = List.copyOf(milestoneCaptureIds);
    }

    @Override
    public ProjectNodeKind kind() {
        return ProjectNodeKind.OPTIMISATION_RUN;
    }
}
