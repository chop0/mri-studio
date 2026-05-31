package ax.xz.mri.ui.tutorial;

/**
 * Stable, typed identifiers for UI controls that tutorials need to point at
 * but which aren't already named by a {@link ax.xz.mri.ui.workbench.CommandId}.
 *
 * <p>Menu items reuse {@code CommandId} as their anchor key via
 * {@link AnchorKey.CommandAnchor} — we don't invent a parallel enum for them.
 * Everything else (wizard buttons, the welcome pane buttons, the procedure
 * editor's Run button) gets a constant here.
 *
 * <p>Keeping this enum small is the point: the more anchors a tutorial can
 * point at, the more existing code has to call {@link UiAnchors#register}
 * at construction time. Add an entry here only when an existing tutorial
 * step has nowhere else to anchor.
 */
public enum UiAnchor {
    /** The wizard's "Back" button. */
    WIZARD_BACK,
    /** The wizard's "Next →" button. */
    WIZARD_NEXT,
    /** The wizard's "Finish" button. */
    WIZARD_FINISH,
    /** The wizard's "Cancel" button. */
    WIZARD_CANCEL,
    /** The wizard's primary name text input (the field on its "Name" step). */
    WIZARD_NAME_INPUT,
}
