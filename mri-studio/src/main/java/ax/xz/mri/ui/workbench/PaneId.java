package ax.xz.mri.ui.workbench;

/** First-class panes in the studio workbench. */
public enum PaneId {
    SPHERE("Bloch Sphere"),
    CROSS_SECTION("Cross-sectional geometry"),
    POINTS("Points of Interest"),
    TIMELINE("Timeline"),
    PHASE_MAPS("Phase Maps"),
    TRACE_ANGLES("Phase Traces"),
    TRACE_MAGNITUDE("Magnitude Trace");

    private final String title;

    PaneId(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
