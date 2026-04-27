package ax.xz.mri.project;

import ax.xz.mri.hardware.HardwareConfig;
import ax.xz.mri.hardware.HardwareConfigEnvelope;
import ax.xz.mri.hardware.HardwareException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Project-owned hardware-execution configuration.
 *
 * <p>Wraps a typed {@link HardwareConfig} produced by some
 * {@link ax.xz.mri.hardware.HardwarePlugin}. The wrapper is what shows up in
 * the project tree and the inspector; the inner config is what the run
 * session hands the device.
 *
 * <p>Persistence uses {@link HardwareConfigEnvelope} so plugins can
 * contribute their own config record types without needing global Jackson
 * subtype registration. The document stores the envelope; helpers
 * {@link #serialise(ObjectMapper)} and
 * {@link #fromEnvelope(ProjectNodeId, String, HardwareConfigEnvelope, ObjectMapper)}
 * round-trip between envelope and typed config.
 */
public record HardwareConfigDocument(
    ProjectNodeId id,
    String name,
    @JsonIgnore HardwareConfig config,
    @JsonProperty("envelope") HardwareConfigEnvelope envelope
) implements ProjectNode {

    public HardwareConfigDocument {
        if (id == null) throw new IllegalArgumentException("id must be non-null");
        if (name == null) name = "";
        if (config == null && envelope == null)
            throw new IllegalArgumentException("HardwareConfigDocument requires a config or envelope");
    }

    /** Construct from a live config object — used when the user creates / edits a doc. */
    public static HardwareConfigDocument of(ProjectNodeId id, String name, HardwareConfig config) {
        return new HardwareConfigDocument(id, name, config, null);
    }

    /** Jackson constructor: prefer the envelope; resolution happens via {@link #resolve}. */
    @JsonCreator
    public static HardwareConfigDocument fromJson(
            @JsonProperty("id") ProjectNodeId id,
            @JsonProperty("name") String name,
            @JsonProperty("envelope") HardwareConfigEnvelope envelope) {
        return new HardwareConfigDocument(id, name, null, envelope);
    }

    /**
     * Materialise the inner {@link HardwareConfig} via the envelope's plugin id.
     * Idempotent — repeated calls return the same instance once resolved.
     */
    public HardwareConfigDocument resolve(ObjectMapper mapper) throws HardwareException {
        if (config != null) return this;
        return new HardwareConfigDocument(id, name, envelope.unwrap(mapper), envelope);
    }

    /** Build a plain envelope for serialisation. */
    public HardwareConfigEnvelope serialise(ObjectMapper mapper) {
        return envelope != null ? envelope : HardwareConfigEnvelope.wrap(config, mapper);
    }

    /** Static helper for the project loader. */
    public static HardwareConfigDocument fromEnvelope(ProjectNodeId id, String name,
                                                      HardwareConfigEnvelope envelope,
                                                      ObjectMapper mapper) throws HardwareException {
        return new HardwareConfigDocument(id, name, envelope.unwrap(mapper), envelope);
    }

    @Override
    public ProjectNodeKind kind() { return ProjectNodeKind.HARDWARE_CONFIG; }

    public HardwareConfigDocument withName(String newName) {
        return new HardwareConfigDocument(id, newName, config, envelope);
    }

    public HardwareConfigDocument withConfig(HardwareConfig newConfig) {
        return new HardwareConfigDocument(id, name, newConfig, null);
    }
}
