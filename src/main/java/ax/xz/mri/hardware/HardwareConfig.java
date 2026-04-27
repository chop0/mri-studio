package ax.xz.mri.hardware;

/**
 * A hardware plugin's configuration record.
 *
 * <p>Marker interface; concrete implementations are records owned by each
 * plugin. The {@link #pluginId()} carries the {@link HardwarePlugin#id()} of
 * the plugin that produced (and can deserialise) this config — the project
 * persistence layer uses it to route deserialisation back to the right
 * plugin via {@link HardwarePluginRegistry}.
 *
 * <p>Concrete configs should be immutable Java records. They participate in
 * Jackson serialisation through an envelope (see
 * {@link HardwareConfigEnvelope}), so plugins are free to expose typed
 * fields without writing custom serialisers.
 */
public interface HardwareConfig {
    /** Identifier of the plugin that owns this config type. Used for serialisation routing. */
    String pluginId();
}
