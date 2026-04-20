package ax.xz.mri.model.simulation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * User-editable simulation environment configuration.
 *
 * <p>Defines the physical parameters needed to run a Bloch simulation:
 * tissue properties, spatial grid, reference frame, field sources, and
 * isochromats. A {@code BlochDataFactory} converts this into a synthetic
 * {@link ax.xz.mri.model.scenario.BlochData}.
 *
 * <p>The simulation is carried out in a rotating frame at angular frequency
 * {@code ω_s = γ · referenceB0Tesla}. This reference is an explicit tuning
 * parameter — it is not derived from any particular field in the list, and
 * there is no privileged "B0 slot" among the fields.
 *
 * <p>Each field source is a {@link FieldDefinition} with a physical shape
 * (eigenfield) and an amplitude schedule ({@link AmplitudeKind} at
 * {@code carrierHz}).
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

    // Rotating-frame reference: ω_s = γ · referenceB0Tesla
    @JsonProperty("reference_b0_tesla") double referenceB0Tesla,

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

    /** Simulation-frame angular frequency (rad/s). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public double omegaSim() {
        return gamma * referenceB0Tesla;
    }

    /** Total number of pulse-sequence control scalars per time step. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public int totalChannelCount() {
        int total = 0;
        for (var field : fields) total += field.channelCount();
        return total;
    }

    public SimulationConfig withReferenceB0Tesla(double newReferenceB0Tesla) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            newReferenceB0Tesla, fields, isochromats);
    }

    public SimulationConfig withFields(List<FieldDefinition> newFields) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, newFields, isochromats);
    }

    public SimulationConfig withIsochromats(List<IsoPoint> newIsochromats) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, fields, newIsochromats);
    }
}
