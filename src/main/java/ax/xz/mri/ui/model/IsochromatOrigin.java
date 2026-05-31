package ax.xz.mri.ui.model;

/**
 * Provenance of an observation point.
 *
 * <p>The UI consumes the origin in two ways. First, panes that need to badge
 * a point (e.g. the Points table) inspect the origin directly. Second, the
 * collection model uses {@code NV_CENTRE} as the cue to mark the entry as
 * locked: NV centre positions are owned by the substance editor, not the
 * Points pane, and dragging them in the cross-section would diverge the
 * Points list from the substance's centres list.
 */
public enum IsochromatOrigin {
    /** Built-in fan (continuous magnetisation FOV defaults). */
    SCENARIO_DEFAULT,
    /** User-created arbitrary observation point. */
    USER,
    /** Mirrors the position of an NV centre in the active NV substance. */
    NV_CENTRE
}
