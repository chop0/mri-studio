package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.sequence.ClipShape;

/** Tool kinds for the sequence editor palette. */
public enum SequenceToolKind {
    // --- Pointer tool (default, selects/moves clips) ---
    SELECT("Select", "Select, move and resize clips", false, null),

    // --- Clip creation tools (click-drag on track to place) ---
    CONSTANT("Constant", "Place a flat-amplitude clip", true, ClipShape.CONSTANT),
    SINC("Sinc", "Place a sinc pulse clip with adjustable lobes", true, ClipShape.SINC),
    TRAPEZOID("Trapezoid", "Place a trapezoid gradient clip", true, ClipShape.TRAPEZOID),
    GAUSSIAN("Gaussian", "Place a Gaussian RF envelope clip", true, ClipShape.GAUSSIAN),
    SPLINE("Spline", "Place a spline clip with draggable control points", true, ClipShape.SPLINE),
    TRIANGLE("Triangle", "Place a triangle ramp clip", true, ClipShape.TRIANGLE),
    SINE("Sine", "Place a sine wave clip with configurable frequency", true, ClipShape.SINE),

    // --- Clip actions (click, immediate) ---
    DELETE_CLIP("Delete Clip", "Remove the selected clip", false, null),
    DUPLICATE_CLIP("Duplicate Clip", "Copy the selected clip", false, null),

    // --- Composite blocks (click, insert pattern) --- Phase 3+
    SPOILER("Spoiler Gradient", "Insert a dephasing gradient block", false, null),
    REFOCUS("Refocusing Block", "Insert a 180\u00b0 RF refocusing block", false, null),
    SLICE_SELECT("Slice-Select Excitation", "Coordinated RF + slice gradient", false, null),
    READOUT("Readout Window", "Frequency-encode gradient window", false, null),

    // --- Overlay toggles ---
    CONSTRAINTS("Constraints", "Toggle hardware limit overlay", false, null);

    private final String displayName;
    private final String description;
    private final boolean creationTool;
    private final ClipShape clipShape;

    SequenceToolKind(String displayName, String description, boolean creationTool, ClipShape clipShape) {
        this.displayName = displayName;
        this.description = description;
        this.creationTool = creationTool;
        this.clipShape = clipShape;
    }

    public String displayName() { return displayName; }
    public String description() { return description; }
    /** Whether this is a clip creation tool (click-drag on track to place). */
    public boolean isCreationTool() { return creationTool; }
    /** The clip shape this tool creates, or null for non-creation tools. */
    public ClipShape clipShape() { return clipShape; }
}
