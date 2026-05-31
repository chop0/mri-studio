package ax.xz.mri.model.simulation;

import ax.xz.mri.project.ProjectNodeId;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Editable simulation environment configuration.
 *
 * <p>A {@code SimulationConfig} ties together three things:
 * <ul>
 *   <li>The {@linkplain #referenceB0Tesla() rotating-frame reference} —
 *       {@code γ · B₀ref} sets {@code ω_s}; offsets from this frame appear as
 *       baseband demodulation.</li>
 *   <li>The {@linkplain #dtSeconds() simulation time step}.</li>
 *   <li>The {@linkplain #circuitId() circuit} — sources, switches, coils,
 *       probes, and wiring. The circuit is the source of truth for what
 *       physical hardware the sequence sees; every other parameter lives on
 *       the substance documents the circuit references.</li>
 * </ul>
 *
 * <p>Spatial layout (extent + resolution), tissue physics (T₁/T₂/γ), and
 * proton density all live on the substance — they are properties of the
 * material being simulated, not of the simulation environment. The grid the
 * simulator uses is derived from the substance at compile time; visualisation
 * viewports are derived from the union of substance extents.
 */
public record SimulationConfig(
    double referenceB0Tesla,
    double dtSeconds,
    ProjectNodeId circuitId
) {
    public SimulationConfig {
        if (!Double.isFinite(referenceB0Tesla))
            throw new IllegalArgumentException("referenceB0Tesla must be finite, got " + referenceB0Tesla);
        if (!(dtSeconds > 0) || !Double.isFinite(dtSeconds))
            throw new IllegalArgumentException("dtSeconds must be a finite positive value, got " + dtSeconds);
    }

    @JsonIgnore
    public double nyquistHz() {
        return 1.0 / (2 * dtSeconds);
    }

    public SimulationConfig withReferenceB0Tesla(double v) {
        return new SimulationConfig(v, dtSeconds, circuitId);
    }

    public SimulationConfig withDtSeconds(double v) {
        return new SimulationConfig(referenceB0Tesla, v, circuitId);
    }

    public SimulationConfig withCircuitId(ProjectNodeId v) {
        return new SimulationConfig(referenceB0Tesla, dtSeconds, v);
    }
}
