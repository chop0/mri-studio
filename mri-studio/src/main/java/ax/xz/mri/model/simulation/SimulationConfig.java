package ax.xz.mri.model.simulation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * User-editable simulation environment configuration.
 *
 * <p>Defines the physical parameters needed to run a Bloch simulation:
 * tissue properties, spatial grid, reference frame, simulation time step, and
 * field sources. A {@code BlochDataFactory} converts this into a synthetic
 * {@link ax.xz.mri.model.scenario.BlochData}.
 *
 * <p>The simulation runs in a rotating frame at angular frequency
 * {@code ω_s = γ · referenceB0Tesla}. The reference is an explicit tuning
 * parameter — there is no privileged "B0 slot" among the fields.
 *
 * <p>The time step {@code dtSeconds} is the integration step used by the Bloch
 * simulator. It governs Bloch–Siegert fast/slow classification (whether a
 * transverse field is kept explicit or folded into the static B<sub>z</sub>
 * correction) and the Nyquist limit of sampled pulse waveforms.
 *
 * <p>Each field source is a {@link FieldDefinition} with a physical shape
 * (eigenfield) and an amplitude schedule ({@link AmplitudeKind} at
 * {@code carrierHz}).
 *
 * <p>Probe points (isochromats) are <em>not</em> stored here — they are a
 * runtime / cross-sectional view concern managed by
 * {@code IsochromatCollectionModel}.
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

    // Simulation integration time step
    @JsonProperty("dt_seconds") double dtSeconds,

    // Field sources
    List<FieldDefinition> fields
) {
    public SimulationConfig {
        fields = fields == null ? List.of() : List.copyOf(fields);
        if (!(dtSeconds > 0) || !Double.isFinite(dtSeconds)) {
            throw new IllegalArgumentException("dtSeconds must be a finite positive value, got " + dtSeconds);
        }
    }

    /** Simulation-frame angular frequency (rad/s). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public double omegaSim() {
        return gamma * referenceB0Tesla;
    }

    /** Larmor frequency of the reference (Hz). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public double larmorHz() {
        return gamma * referenceB0Tesla / (2 * Math.PI);
    }

    /** Nyquist frequency of the simulation step (Hz). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public double nyquistHz() {
        return 1.0 / (2 * dtSeconds);
    }

    /** Total number of pulse-sequence control scalars per time step. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public int totalChannelCount() {
        int total = 0;
        for (var field : fields) total += field.channelCount();
        return total;
    }

    public SimulationConfig withT1Ms(double v) {
        return new SimulationConfig(v, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, dtSeconds, fields);
    }

    public SimulationConfig withT2Ms(double v) {
        return new SimulationConfig(t1Ms, v, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, dtSeconds, fields);
    }

    public SimulationConfig withGamma(double v) {
        return new SimulationConfig(t1Ms, t2Ms, v, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, dtSeconds, fields);
    }

    public SimulationConfig withSliceHalfMm(double v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, v, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, dtSeconds, fields);
    }

    public SimulationConfig withFovZMm(double v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, v, fovRMm, nZ, nR,
            referenceB0Tesla, dtSeconds, fields);
    }

    public SimulationConfig withFovRMm(double v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, v, nZ, nR,
            referenceB0Tesla, dtSeconds, fields);
    }

    public SimulationConfig withNZ(int v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, v, nR,
            referenceB0Tesla, dtSeconds, fields);
    }

    public SimulationConfig withNR(int v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, v,
            referenceB0Tesla, dtSeconds, fields);
    }

    public SimulationConfig withReferenceB0Tesla(double v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            v, dtSeconds, fields);
    }

    public SimulationConfig withDtSeconds(double v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, v, fields);
    }

    public SimulationConfig withFields(List<FieldDefinition> newFields) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, dtSeconds, newFields);
    }
}
