package ax.xz.mri.ui.preview;

import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.project.ProjectNodeId;

import java.util.List;

/**
 * "Open the schematic for this circuit and light up these components and
 * wires." Published by the clip inspector's "Show in schematic" button and
 * consumed by the workbench, which switches the user to the right tab and
 * applies the highlight overlay on the schematic canvas.
 *
 * <p>The request is intentionally a value object — the workbench is the one
 * that knows how to map a {@code circuitId} to an open editor tab, switch
 * to the schematic sub-tab, and call
 * {@link ax.xz.mri.ui.workbench.pane.schematic.SchematicPane#showPathHighlight}.
 *
 * @param circuitId circuit document the path lives in
 * @param components ordered components on the path (source first, coil last)
 * @param wireIds wire ids on the path
 * @param description human-readable label, e.g. "RF I → RF Coil"
 */
public record SchematicHighlightRequest(
    ProjectNodeId circuitId,
    List<ComponentId> components,
    List<String> wireIds,
    String description
) {
    public SchematicHighlightRequest {
        if (circuitId == null) throw new IllegalArgumentException("circuitId must not be null");
        components = List.copyOf(components);
        wireIds = List.copyOf(wireIds);
    }
}
