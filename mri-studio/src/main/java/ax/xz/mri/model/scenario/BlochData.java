package ax.xz.mri.model.scenario;

import ax.xz.mri.model.field.FieldMap;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** Top-level document loaded from bloch_data.json. */
public record BlochData(
    @JsonProperty("field")     FieldMap              field,
    @JsonProperty("iso")       List<IsochromatDef>   iso,
    @JsonProperty("scenarios") Map<String, Scenario> scenarios
) {
    /**
     * One isochromat definition from the JSON {@code "iso"} array.
     * Serialised as a 3-element JSON array: {@code [name, colour, inSlice]}.
     */
    public record IsochromatDef(String name, String colour, boolean inSlice) {}
}
