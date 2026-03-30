package ax.xz.mri.ui.workbench;

/** First-class panes in the studio workbench. */
public enum PaneId {
    SPHERE("Bloch Sphere"),
    GEOMETRY("Geometry"),
    POINTS("Points of Interest"),
    TIMELINE("Timeline"),
    PHASE_MAP_Z("Phase Map Z"),
    PHASE_MAP_R("Phase Map R"),
    TRACE_PHASE("Phase Trace"),
    TRACE_POLAR("Polar Trace"),
    TRACE_MAGNITUDE("Magnitude Trace");

    private final String title;

    PaneId(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
