package ax.xz.mri.project;

import java.util.List;

/** Imported scenario root from a legacy JSON source. */
public record ImportedScenarioDocument(
    ProjectNodeId id,
    ProjectNodeId importLinkId,
    String name,
    String sourceScenarioName,
    boolean iterative,
    List<String> iterationKeys,
    ProjectNodeId importedRunId,
    List<ProjectNodeId> directCaptureIds
) implements ProjectNode {
    public ImportedScenarioDocument {
        iterationKeys = List.copyOf(iterationKeys);
        directCaptureIds = List.copyOf(directCaptureIds);
    }

    @Override
    public ProjectNodeKind kind() {
        return ProjectNodeKind.IMPORTED_SCENARIO;
    }
}
