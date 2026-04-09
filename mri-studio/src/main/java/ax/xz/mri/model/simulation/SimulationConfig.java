package ax.xz.mri.model.simulation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * User-editable simulation environment configuration.
 *
 * <p>Defines the physical parameters needed to run a Bloch simulation:
 * tissue properties, spatial grid, field sources, and isochromats.
 * A {@link BlochDataFactory} converts this into a synthetic
 * {@link ax.xz.mri.model.scenario.BlochData}.
 *
 * <p>Each field source is modelled as a {@link FieldDefinition} with
 * control type, amplitude bounds, baseband frequency, and a reference
 * to an eigenfield document that defines the spatial shape.
 */
public record SimulationConfig(
    // Tissue / nucleus
    @JsonProperty("t1_ms") double t1Ms,
    @JsonProperty("t2_ms") double t2Ms,
    double gamma,

    // Spatial grid
    @JsonProperty("slice_half_mm") double sliceHalfMm,
    @JsonProperty("fov_z_mm") double fovZMm,
    @JsonProperty("fov_r_mm") double fovRMm,
    @JsonProperty("n_z") int nZ,
    @JsonProperty("n_r") int nR,

    // Field sources
    List<FieldDefinition> fields,

    // Isochromats (points of interest)
    List<IsoPoint> isochromats
) {
    /** A spatial point to simulate with a display label and colour. */
    public record IsoPoint(
        @JsonProperty("r_mm") double rMm,
        @JsonProperty("z_mm") double zMm,
        String name,
        String colour
    ) {}

    public SimulationConfig {
        fields = fields == null ? List.of() : List.copyOf(fields);
        isochromats = isochromats == null ? List.of() : List.copyOf(isochromats);
    }

    /**
     * Extract the nominal B0 field strength (Tesla) from the field definitions.
     * Finds the first BINARY DC field and returns its maxAmplitude.
     * Returns 0 if no such field exists.
     */
    public double b0Tesla() {
        return fields.stream()
            .filter(f -> f.controlType() == ControlType.BINARY && f.isDC())
            .mapToDouble(FieldDefinition::maxAmplitude)
            .findFirst()
            .orElse(0.0);
    }

    public SimulationConfig withFields(List<FieldDefinition> newFields) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR, newFields, isochromats);
    }

    public SimulationConfig withIsochromats(List<IsoPoint> newIsochromats) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR, fields, newIsochromats);
    }
}
