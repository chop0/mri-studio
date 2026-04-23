package ax.xz.mri.ui.workbench.pane;

import ax.xz.mri.model.sequence.ClipKind;

/**
 * Tool identities for the sequence editor palette.
 *
 * <p>Each tool either creates a clip of a specific {@link ClipKind} (click-drag
 * on an empty track to place one) or performs an immediate action on the
 * current selection.
 */
public enum SequenceToolKind {
    // ── Pointer tool (default, selects/moves clips) ─────────────────────────
    SELECT("Select", "Select, move and resize clips", null),

    // ── Clip creation tools ─────────────────────────────────────────────────
    CONSTANT("Constant",   "Place a flat-amplitude clip",                            ClipKind.CONSTANT),
    SINC("Sinc",           "Place a sinc pulse clip with adjustable lobes",          ClipKind.SINC),
    TRAPEZOID("Trapezoid", "Place a trapezoid gradient clip",                        ClipKind.TRAPEZOID),
    GAUSSIAN("Gaussian",   "Place a Gaussian RF envelope clip",                      ClipKind.GAUSSIAN),
    SPLINE("Spline",       "Place a spline clip with draggable control points",     ClipKind.SPLINE),
    TRIANGLE("Triangle",   "Place a triangle ramp clip",                             ClipKind.TRIANGLE),
    SINE("Sine",           "Place a sine wave clip with configurable frequency",    ClipKind.SINE),

    // ── Immediate actions (on selection) ────────────────────────────────────
    DELETE_CLIP("Delete Clip",       "Remove the selected clip", null),
    DUPLICATE_CLIP("Duplicate Clip", "Copy the selected clip",   null),

    // ── Composite blocks (phase 3+, currently disabled) ─────────────────────
    SPOILER("Spoiler Gradient",       "Insert a dephasing gradient block",       null),
    REFOCUS("Refocusing Block",       "Insert a 180\u00b0 RF refocusing block",  null),
    SLICE_SELECT("Slice-Select Excitation", "Coordinated RF + slice gradient",    null),
    READOUT("Readout Window",         "Frequency-encode gradient window",        null),

    // ── Overlay toggles ─────────────────────────────────────────────────────
    CONSTRAINTS("Constraints", "Toggle hardware limit overlay", null);

    private final String displayName;
    private final String description;
    private final ClipKind clipKind;

    SequenceToolKind(String displayName, String description, ClipKind clipKind) {
        this.displayName = displayName;
        this.description = description;
        this.clipKind = clipKind;
    }

    public String displayName() { return displayName; }
    public String description() { return description; }
    /** Whether this is a clip creation tool. */
    public boolean isCreationTool() { return clipKind != null; }
    /** The clip kind this tool creates, or {@code null} for pointer/action tools. */
    public ClipKind clipKind() { return clipKind; }
}
