package ax.xz.mri.project;

import java.util.List;

/** Stored index of imported scenarios and generated node ids for one import source. */
public record ImportIndexDocument(
    ProjectNodeId id,
    ProjectNodeId importLinkId,
    List<ProjectNodeId> scenarioIds
) {
    public ImportIndexDocument {
        scenarioIds = List.copyOf(scenarioIds);
    }

    public int schema() {
        return 1;
    }
}
