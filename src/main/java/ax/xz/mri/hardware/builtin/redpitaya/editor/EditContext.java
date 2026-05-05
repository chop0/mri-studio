package ax.xz.mri.hardware.builtin.redpitaya.editor;

import ax.xz.mri.hardware.builtin.redpitaya.RedPitayaConfig;

import java.util.function.Function;

/**
 * Glue handed to every sub-tab so it can read the current config and apply
 * an immutable update. The editor swaps {@link #current()} on
 * {@code setConfig(...)} and notifies on every edit; tabs only see the
 * current snapshot.
 */
public interface EditContext {
    RedPitayaConfig current();
    void edit(Function<RedPitayaConfig, RedPitayaConfig> mutator);
}
