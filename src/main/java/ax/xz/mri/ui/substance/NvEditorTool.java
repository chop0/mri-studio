package ax.xz.mri.ui.substance;

/**
 * Active interaction mode for the {@link NvScatter3DCanvas}. The Tool
 * palette in the substance editor binds to a {@code Tool} property; mouse
 * handlers on the canvas branch on the active tool.
 *
 * <ul>
 *   <li>{@link #SELECT} — click to pick an NV, drag to move it.</li>
 *   <li>{@link #ADD} — click in empty space drops a new NV at the cursor
 *       (projected onto the active constraint plane).</li>
 *   <li>{@link #DELETE} — click an NV to remove it.</li>
 *   <li>{@link #ORBIT} — drag empty space to orbit the camera.</li>
 * </ul>
 */
public enum NvEditorTool {
    SELECT, ADD, DELETE, ORBIT;

    public String displayName() {
        return switch (this) {
            case SELECT -> "Select";
            case ADD    -> "Add";
            case DELETE -> "Delete";
            case ORBIT  -> "Orbit";
        };
    }

    public String hint() {
        return switch (this) {
            case SELECT -> "Click an NV to select. Drag to move under the active constraint.";
            case ADD    -> "Click empty space to drop a new NV on the constraint plane.";
            case DELETE -> "Click an NV to delete it.";
            case ORBIT  -> "Drag empty space to orbit the camera. Scroll to zoom.";
        };
    }
}
