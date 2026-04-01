package ax.xz.mri.project;

import java.util.List;

/** Imported optimisation-like run synthesized from an iterative legacy scenario. */
public record ImportedOptimisationRunDocument(
    ProjectNodeId id,
    ProjectNodeId importLinkId,
    ProjectNodeId scenarioId,
    String name,
    List<ProjectNodeId> captureIds,
    ProjectNodeId firstCaptureId,
    ProjectNodeId latestCaptureId,
    ProjectNodeId bestCaptureId,
    List<ProjectNodeId> milestoneCaptureIds
) implements ProjectNode {
    public ImportedOptimisationRunDocument {
        captureIds = List.copyOf(captureIds);
        milestoneCaptureIds = List.copyOf(milestoneCaptureIds);
    }

    @Override
    public ProjectNodeKind kind() {
        return ProjectNodeKind.IMPORTED_OPTIMISATION_RUN;
    }
}
