package ax.xz.mri.ui.workbench.pane.schematic.presenter;

import ax.xz.mri.ui.workbench.pane.schematic.ComponentGeometry;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.VBox;

/**
 * UI behaviour for a concrete {@link ax.xz.mri.model.circuit.CircuitComponent}
 * kind. Each component type has one presenter that owns its:
 * <ul>
 *   <li>canvas {@linkplain #geometry() geometry} — dimensions and terminal
 *       positions used by both rendering and hit testing;</li>
 *   <li>canvas {@linkplain #drawBody(GraphicsContext) body drawing} — the
 *       outer shell (transforms, halo, hover ring, terminal dots) is handled
 *       by the caller; the presenter just draws the body in local space;</li>
 *   <li>inspector panel contents;</li>
 *   <li>auto-layout column + human-readable name.</li>
 * </ul>
 *
 * <p>Adding a new component type = one new presenter class + one arm in the
 * {@link ComponentPresenters#of} switch + one entry in
 * {@link ComponentPresenters#paletteEntries}. No edits to the compiler,
 * the renderer wrapper, the geometry dispatcher, the inspector dispatcher,
 * the palette, the auto-layout, or the context-menu code.
 */
public interface ComponentPresenter {

    /** Dimensions and terminal positions in local coordinates. */
    ComponentGeometry geometry();

    /**
     * Draw the component body at the canvas origin (caller has translated,
     * rotated, mirrored; halo/hover and terminal dots are drawn around this).
     */
    void drawBody(GraphicsContext g);

    /** Populate the inspector panel with this component's editable fields. */
    void buildInspector(VBox container, InspectorEnv env);

    /** Which auto-layout column this component belongs to. */
    int autoLayoutColumn();

    /** Title shown at the top of the inspector. */
    String displayName();
}
