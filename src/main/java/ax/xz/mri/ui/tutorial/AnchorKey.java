package ax.xz.mri.ui.tutorial;

import ax.xz.mri.ui.workbench.CommandId;

/**
 * Typed identifier for a UI control a tutorial step points at.
 *
 * <p>Sealed over two cases:
 * <ul>
 *   <li>{@link CommandAnchor} — re-uses the existing
 *       {@link CommandId} enum. Every menu item in {@code StudioShell} is
 *       already keyed by one of these, so referencing them costs nothing.</li>
 *   <li>{@link UiNodeAnchor} — for non-menu controls (wizard buttons,
 *       the welcome pane's tutorial buttons, …) named by the small
 *       {@link UiAnchor} enum.</li>
 * </ul>
 */
public sealed interface AnchorKey {

    record CommandAnchor(CommandId id) implements AnchorKey {
        public CommandAnchor {
            if (id == null) throw new IllegalArgumentException("CommandId must not be null");
        }
    }

    record UiNodeAnchor(UiAnchor anchor) implements AnchorKey {
        public UiNodeAnchor {
            if (anchor == null) throw new IllegalArgumentException("UiAnchor must not be null");
        }
    }

    /** Convenience factory: command-keyed anchor. */
    static AnchorKey of(CommandId id) { return new CommandAnchor(id); }

    /** Convenience factory: node-keyed anchor. */
    static AnchorKey of(UiAnchor anchor) { return new UiNodeAnchor(anchor); }
}
