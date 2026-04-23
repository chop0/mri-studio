package ax.xz.mri.model.circuit;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stable identity of a component within a {@link CircuitDocument}.
 *
 * <p>Serialises as a bare string so {@code Map<ComponentId, ...>} round-trips
 * cleanly through Jackson — otherwise the deserialiser reconstructs keys as
 * {@code ComponentId[value="..."]} strings.
 */
public record ComponentId(@JsonValue String value) {
    public ComponentId {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("ComponentId must be non-blank");
    }

    @JsonCreator
    public static ComponentId of(String value) {
        return new ComponentId(value);
    }
}
