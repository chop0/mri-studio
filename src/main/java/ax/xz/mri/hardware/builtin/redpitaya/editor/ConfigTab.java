package ax.xz.mri.hardware.builtin.redpitaya.editor;

import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaConfig;
import javafx.scene.Node;

/**
 * Common shape for the editor's sub-tabs.
 *
 * <p>{@link #refresh(RedPitayaConfig)} is called by the host editor on
 * external mutations (undo, revert, setConfig) so each tab can rebind its
 * controls without firing change listeners.
 */
interface ConfigTab {
    Node view();
    void refresh(RedPitayaConfig cfg);
}
