package ax.xz.mri.hardware;

import javafx.scene.Node;

/**
 * UI fragment supplied by a {@link HardwarePlugin} for editing its
 * {@link HardwareConfig}. Hosted both in the new-hardware-config wizard
 * (when the user picks the plugin) and in the inspector / dedicated config
 * pane after the document is created.
 *
 * <p>Editors operate against an immutable {@link HardwareConfig} instance:
 * each user edit produces a new config via {@link #snapshot()}, which the
 * caller is expected to persist. The editor is single-use — the host
 * creates a new instance whenever it needs to edit a different config.
 */
public interface HardwareConfigEditor {
    /** The JavaFX node to embed; must remain stable across the editor's lifetime. */
    Node view();

    /** Push a new config into the editor (e.g. on undo, or when reloading). */
    void setConfig(HardwareConfig config);

    /** Produce the current config from the editor's UI state. */
    HardwareConfig snapshot();

    /**
     * Optional listener invoked when the user makes any edit. The host wires
     * this to push live changes back to the document; the listener may be
     * called many times per second so it should be lightweight.
     */
    default void setOnEdited(Runnable listener) { /* no-op default */ }
}
