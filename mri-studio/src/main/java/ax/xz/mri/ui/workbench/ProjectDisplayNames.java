package ax.xz.mri.ui.workbench;

import ax.xz.mri.project.BookmarkKind;
import ax.xz.mri.project.ProjectNode;
import ax.xz.mri.project.ProjectNodeKind;
import ax.xz.mri.project.RunBookmarkDocument;

/** Human-facing labels for the semantic project tree. */
public final class ProjectDisplayNames {
    private ProjectDisplayNames() {
    }

    public static String label(ProjectNode node) {
        return switch (node.kind()) {
            case IMPORT_LINK -> node.name();
            case IMPORTED_SCENARIO -> "Scenario: " + node.name();
            case IMPORTED_OPTIMISATION_RUN, OPTIMISATION_RUN -> "Optimisation: " + node.name();
            case IMPORTED_CAPTURE, CAPTURE -> "Capture: " + node.name();
            case SEQUENCE -> "Sequence: " + node.name();
            case SIMULATION -> "Simulation: " + node.name();
            case OPTIMISATION_CONFIG -> "Optimisation Config: " + node.name();
            case SEQUENCE_SNAPSHOT -> node.name();
            case RUN_BOOKMARK -> bookmarkLabel((RunBookmarkDocument) node);
        };
    }

    public static String bookmarkLabel(RunBookmarkDocument bookmark) {
        return switch (bookmark.bookmarkKind()) {
            case FIRST -> "First Iteration";
            case LATEST -> "Last Iteration";
            case BEST -> "Best Iteration";
            case MILESTONE -> bookmark.name();
        };
    }
}
