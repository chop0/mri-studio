package ax.xz.mri.project;

/** Semantic kinds in the project tree. */
public enum ProjectNodeKind {
    IMPORT_LINK,
    IMPORTED_SCENARIO,
    IMPORTED_OPTIMISATION_RUN,
    IMPORTED_CAPTURE,
    SEQUENCE,
    SIMULATION,
    CAPTURE,
    SEQUENCE_SNAPSHOT,
    OPTIMISATION_CONFIG,
    OPTIMISATION_RUN,
    RUN_BOOKMARK
}
