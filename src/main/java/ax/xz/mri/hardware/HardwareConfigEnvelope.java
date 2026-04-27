package ax.xz.mri.hardware;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Persistence envelope for a {@link HardwareConfig}.
 *
 * <p>Java records implementing a non-sealed marker interface can't be
 * deserialised polymorphically without runtime registration of every
 * subtype. We side-step that by persisting an envelope:
 * <pre>{@code
 *   { "pluginId": "ax.xz.mri.mock", "config": { ...plugin-specific JSON... } }
 * }</pre>
 *
 * <p>The {@code pluginId} routes deserialisation to the right plugin via
 * {@link HardwarePluginRegistry}, which then materialises a typed config
 * record. Serialisation is symmetric — we just write the plugin id and the
 * raw config tree.
 */
public record HardwareConfigEnvelope(
    @JsonProperty("pluginId") String pluginId,
    @JsonProperty("config") JsonNode config
) {
    /** Wrap a typed config for storage. */
    public static HardwareConfigEnvelope wrap(HardwareConfig config, ObjectMapper mapper) {
        return new HardwareConfigEnvelope(config.pluginId(), mapper.valueToTree(config));
    }

    /** Resolve the envelope back to a typed config via the plugin registry. */
    public HardwareConfig unwrap(ObjectMapper mapper) throws HardwareException {
        var plugin = HardwarePluginRegistry.byId(pluginId).orElseThrow(() ->
            new HardwareException("Unknown hardware plugin: " + pluginId));
        try {
            return plugin.deserialiseConfig(config, mapper);
        } catch (com.fasterxml.jackson.databind.JsonMappingException ex) {
            throw new HardwareException("Failed to read config for plugin " + pluginId + ": " + ex.getMessage(), ex);
        }
    }
}
