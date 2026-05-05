package ax.xz.mri.project;

import ax.xz.mri.hardware.HardwareConfig;
import ax.xz.mri.hardware.HardwareConfigEnvelope;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Project-owned hardware-execution configuration.
 *
 * <p>The on-disk shape is exactly the three fields below: {@code id},
 * {@code name}, {@code envelope}. The typed {@link HardwareConfig} is
 * <em>not</em> a record component — it's derived from {@link #envelope}
 * via {@link #config()}, which unwraps through the plugin registry on
 * demand. Earlier versions had {@code config} as a component with
 * {@link com.fasterxml.jackson.annotation.JsonIgnore @JsonIgnore}, which
 * tripped a Jackson record-handling check at deserialiser-build time
 * ("Could not find creator property with name 'config'") — fixed by
 * just not having the field.
 */
public record HardwareConfigDocument(
    @JsonProperty("id") ProjectNodeId id,
    @JsonProperty("name") String name,
    @JsonProperty("envelope") HardwareConfigEnvelope envelope
) implements ProjectNode {

    /**
     * Shared mapper for envelope unwrap. Lenient on unknown properties so
     * older project files (with extra fields) don't reject load.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public HardwareConfigDocument {
        if (id == null) throw new IllegalArgumentException("id must be non-null");
        if (name == null) name = "";
    }

    @JsonCreator
    public static HardwareConfigDocument fromJson(
            @JsonProperty("id") ProjectNodeId id,
            @JsonProperty("name") String name,
            @JsonProperty("envelope") HardwareConfigEnvelope envelope) {
        return new HardwareConfigDocument(id, name, envelope);
    }

    /** Construct from a live config object — used when the user creates / edits a doc. */
    public static HardwareConfigDocument of(ProjectNodeId id, String name, HardwareConfig config) {
        return new HardwareConfigDocument(id, name,
            config == null ? null : HardwareConfigEnvelope.wrap(config, MAPPER));
    }

    /**
     * The typed config, derived from {@link #envelope} via the plugin
     * registry. Returns {@code null} if no envelope is set or if the
     * envelope's plugin isn't loaded — callers must null-check.
     */
    public HardwareConfig config() {
        if (envelope == null) return null;
        try {
            return envelope.unwrap(MAPPER);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public ProjectNodeKind kind() { return ProjectNodeKind.HARDWARE_CONFIG; }

    public HardwareConfigDocument withName(String newName) {
        return new HardwareConfigDocument(id, newName, envelope);
    }

    public HardwareConfigDocument withConfig(HardwareConfig newConfig) {
        return new HardwareConfigDocument(id, name,
            newConfig == null ? null : HardwareConfigEnvelope.wrap(newConfig, MAPPER));
    }
}
