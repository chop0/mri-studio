package ax.xz.mri.model.circuit;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Reference to one named port on a specific component.
 *
 * <p>Port names are canonical per component kind — e.g. every {@code Coil}
 * has {@code "a"} and {@code "b"}; every {@code Switch} has {@code "a"},
 * {@code "b"}, and {@code "ctl"}.
 */
public record ComponentTerminal(
    @JsonProperty("component") ComponentId componentId,
    @JsonProperty("port") String port
) {
    public ComponentTerminal {
        if (componentId == null) throw new IllegalArgumentException("ComponentTerminal.componentId must not be null");
        if (port == null || port.isBlank()) throw new IllegalArgumentException("ComponentTerminal.port must be non-blank");
    }
}
