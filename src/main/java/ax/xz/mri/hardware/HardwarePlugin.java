package ax.xz.mri.hardware;

import ax.xz.mri.model.sequence.SequenceChannel;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Service-loaded entry point for one kind of hardware device.
 *
 * <p>Plugins are discovered via {@link java.util.ServiceLoader} —
 * {@code module-info.java} declares
 * {@code uses ax.xz.mri.hardware.HardwarePlugin;}, and each provider declares
 * {@code provides ax.xz.mri.hardware.HardwarePlugin with ...;}. Built-in
 * plugins live under {@code ax.xz.mri.hardware.builtin}; future external
 * plugins ship as JARs that contribute their own provider declarations.
 *
 * <p>The plugin interface deliberately separates what the device <em>can do</em>
 * (advertised as {@link Capabilities}) from a particular configuration and
 * the runtime device handle. This lets the editor and wizards reason about
 * routing without needing to open a connection.
 */
public interface HardwarePlugin {

    /** Unique, stable id (e.g. {@code "ax.xz.mri.mock"}). Survives across versions. */
    String id();

    /** Display name shown in the wizard and inspector. */
    String displayName();

    /** Sentence describing the plugin — surfaced in wizard hint text. */
    String description();

    /** Static description of what this plugin can drive. Used by the editor for routing UI. */
    Capabilities capabilities();

    /** Defaults for a brand-new {@link HardwareConfig} of this plugin's type. */
    HardwareConfig defaultConfig();

    /**
     * UI fragment for editing this plugin's config. Returning {@code null}
     * means the plugin has no configuration surface — the wizard will skip
     * the config step and the inspector will show only summary information.
     */
    HardwareConfigEditor configEditor(HardwareConfig initial);

    /**
     * Open a runtime device handle. The returned {@link HardwareDevice} owns
     * its connection until {@link HardwareDevice#close()} is called.
     */
    HardwareDevice open(HardwareConfig config) throws HardwareException;

    /**
     * Re-hydrate a JSON payload into a typed {@link HardwareConfig} record.
     * Called by the persistence layer after seeing this plugin's id in the
     * envelope. Plugins typically delegate to {@link com.fasterxml.jackson.databind.ObjectMapper#treeToValue}.
     */
    HardwareConfig deserialiseConfig(JsonNode raw, com.fasterxml.jackson.databind.ObjectMapper mapper) throws com.fasterxml.jackson.databind.JsonMappingException;

    /**
     * What this plugin can drive: addressable output channels (sequence
     * tracks may target any of these via {@code Track.hardwareChannel}),
     * named probe inputs (the device returns one trace per probe), and timing
     * constraints.
     */
    record Capabilities(
        List<SequenceChannel> outputChannels,
        List<String> probeNames,
        double maxSampleRateHz,
        double minDtSeconds
    ) {
        public Capabilities {
            outputChannels = outputChannels == null ? List.of() : List.copyOf(outputChannels);
            probeNames = probeNames == null ? List.of() : List.copyOf(probeNames);
        }
    }
}
