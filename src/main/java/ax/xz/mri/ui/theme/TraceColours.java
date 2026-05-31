package ax.xz.mri.ui.theme;

import javafx.scene.paint.Color;

/**
 * Single source for the simulator-vs-hardware trace colours used by the
 * trace plot panes, the heat-map probes overlay, and the timeline output rows.
 *
 * <p>Every place that draws a trace should reach into this class so that:
 * <ol>
 *   <li>simulator and hardware traces are visually distinguishable everywhere;
 *   <li>changing the brand-blue once flows through to all renderers;
 *   <li>fill versus stroke uses consistent alpha so layered traces remain
 *       legible.
 * </ol>
 */
public final class TraceColours {
    private TraceColours() {}

    /** Stroke colour for a simulator-produced trace. */
    public static final Color SIM_LINE   = Color.web("#0b5cad");
    /** Fill (under-line area) for a simulator-produced trace. */
    public static final Color SIM_FILL   = Color.web("#0b5cad", 0.12);
    /** Probe ring / point colour for sim-produced trajectories. */
    public static final Color SIM_PROBE  = Color.web("#0b5cad");

    /** Stroke colour for a hardware-captured trace. */
    public static final Color HW_LINE    = Color.web("#a45a00");
    /** Fill (under-line area) for a hardware-captured trace. */
    public static final Color HW_FILL    = Color.web("#a45a00", 0.12);
    /** Probe ring / point colour for hardware-captured trajectories. */
    public static final Color HW_PROBE   = Color.web("#a45a00");

    /** Crosshair / cursor marker drawn over either trace family. */
    public static final Color CURSOR     = Color.web("#e06000");
    /** Grid lines and axis ticks. */
    public static final Color GRID       = Color.web("#dde0e5");
    /** Axis labels and tick text. */
    public static final Color AXIS_TEXT  = Color.web("#5c6571");
}
