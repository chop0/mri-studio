package ax.xz.mri.project;

import ax.xz.mri.model.scenario.BlochData;

import java.io.File;
import java.util.List;
import java.util.Map;

/** Generated project-side documents and runtime state for one imported JSON source. */
public record ImportedProjectBundle(
    ImportLinkDocument importLink,
    ImportIndexDocument importIndex,
    Map<ProjectNodeId, ProjectNode> nodes,
    Map<ProjectNodeId, List<ProjectNodeId>> children,
    File sourceFile,
    BlochData blochData
) {
}
