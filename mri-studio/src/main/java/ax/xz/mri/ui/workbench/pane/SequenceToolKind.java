package ax.xz.mri.ui.workbench.pane;

/** Tool kinds for the sequence editor palette. */
public enum SequenceToolKind {
    // --- Drawing tools (toggle, one active at a time) ---
    DRAW("Draw", "Freehand sample-by-sample editing", true),
    RECTANGLE("Rectangle", "Draw a flat-top rectangular pulse", true),
    SINC("Sinc", "Stamp a sinc pulse with adjustable lobes", true),
    TRAPEZOID("Trapezoid", "Gradient trapezoid with ramp control", true),
    GAUSSIAN("Gaussian", "Gaussian RF envelope", true),

    // --- Segment actions (click, immediate) ---
    INSERT_SEGMENT("Insert Segment", "Insert a new segment after selection", false),
    DELETE_SEGMENT("Delete Segment", "Remove the selected segment", false),
    DUPLICATE_SEGMENT("Duplicate Segment", "Copy the selected segment", false),

    // --- Composite blocks (click, insert pattern) ---
    SPOILER("Spoiler Gradient", "Insert a dephasing gradient block", false),
    REFOCUS("Refocusing Block", "Insert a 180\u00b0 RF refocusing block", false),
    SLICE_SELECT("Slice-Select Excitation", "Coordinated RF + slice gradient", false),
    READOUT("Readout Window", "Frequency-encode gradient window", false),

    // --- Overlay toggles ---
    CONSTRAINTS("Constraints", "Toggle hardware limit overlay", false);

    private final String displayName;
    private final String description;
    private final boolean drawingTool;

    SequenceToolKind(String displayName, String description, boolean drawingTool) {
        this.displayName = displayName;
        this.description = description;
        this.drawingTool = drawingTool;
    }

    public String displayName() { return displayName; }
    public String description() { return description; }
    public boolean isDrawingTool() { return drawingTool; }
}
