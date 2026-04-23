package ax.xz.mri.ui.workbench;

/** First-class panes in the studio workbench. */
public enum PaneId {
    EXPLORER("Explorer"),
    INSPECTOR("Inspector"),
    SPHERE("Bloch Sphere"),
    CROSS_SECTION("Cross-sectional geometry"),
    POINTS("Points of Interest"),
    TIMELINE("Timeline"),
    PHASE_MAP_Z("Phase Map Z"),
    PHASE_MAP_R("Phase Map R"),
    TRACE_PHASE("Phase Trace"),
    TRACE_POLAR("Polar Trace"),
    TRACE_MAGNITUDE("Magnitude Trace"),
    MESSAGES("Messages"),
    SEQUENCE_EDITOR("Sequence Editor"),
    SIM_CONFIG_EDITOR("Simulation Config"),
    EIGENFIELD_EDITOR("Eigenfield Editor");

    private final String title;

    PaneId(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
