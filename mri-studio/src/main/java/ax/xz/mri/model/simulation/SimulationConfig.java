package ax.xz.mri.model.simulation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * User-editable simulation environment configuration.
 *
 * <p>Defines the physical parameters needed to run a Bloch simulation without
 * importing real MRI data. A {@link ax.xz.mri.model.simulation.BlochDataFactory}
 * converts this into a synthetic {@link ax.xz.mri.model.scenario.BlochData}.
 */
public record SimulationConfig(
    // Physics
    @JsonProperty("b0_tesla") double b0Tesla,
    @JsonProperty("t1_ms") double t1Ms,
    @JsonProperty("t2_ms") double t2Ms,
    double gamma,

    // Spatial
    FieldPreset preset,
    @JsonProperty("slice_half_mm") double sliceHalfMm,
    @JsonProperty("fov_z_mm") double fovZMm,
    @JsonProperty("fov_r_mm") double fovRMm,
    @JsonProperty("n_z") int nZ,
    @JsonProperty("n_r") int nR,
    @JsonProperty("dBz_linear_ut_per_mm") double dBzLinearUtPerMm,

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
        isochromats = isochromats == null ? List.of() : List.copyOf(isochromats);
    }

    /** Sensible defaults for a 3T uniform-field single-slice simulation. */
    public static SimulationConfig defaults() {
        return new SimulationConfig(
            3.0,            // B0 = 3 T
            1000.0,         // T1 = 1000 ms
            100.0,          // T2 = 100 ms
            267.522e6,      // proton gamma (rad/s/T)
            FieldPreset.UNIFORM,
            5.0,            // slice half = 5 mm
            20.0,           // FOV z = 20 mm
            30.0,           // FOV r = 30 mm
            50,             // 50 z-grid points
            5,              // 5 r-grid points
            0.0,            // no linear gradient
            List.of(
                new IsoPoint(0, 0, "Centre", "#e06000"),
                new IsoPoint(0, 2, "z = 2 mm", "#1976d2"),
                new IsoPoint(0, -2, "z = -2 mm", "#2e7d32")
            )
        );
    }
}
