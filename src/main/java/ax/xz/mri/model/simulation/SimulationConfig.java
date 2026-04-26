package ax.xz.mri.model.simulation;

import ax.xz.mri.project.ProjectNodeId;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * User-editable simulation environment configuration.
 *
 * <p>Holds the non-circuit physics knobs — tissue relaxation, gyromagnetic
 * ratio, spatial grid, rotating-frame reference, integration step — and a
 * pointer to the active {@link ax.xz.mri.model.circuit.CircuitDocument}
 * that describes the sources, switches, coils, probes, and wiring. The
 * circuit is the source of truth for what physical hardware the sequence
 * sees; {@code SimulationConfig} is everything else.
 */
public record SimulationConfig(
    double t1Ms,
    double t2Ms,
    double gamma,

    double sliceHalfMm,
    double fovZMm,
    double fovRMm,
    int nZ,
    int nR,

    double referenceB0Tesla,

    double dtSeconds,

    ProjectNodeId circuitId
) {
    public SimulationConfig {
        if (!(dtSeconds > 0) || !Double.isFinite(dtSeconds)) {
            throw new IllegalArgumentException("dtSeconds must be a finite positive value, got " + dtSeconds);
        }
    }

    /** Build a config from a {@link PhysicsParams} bundle plus the chosen circuit and reference field. */
    public static SimulationConfig fromPhysics(PhysicsParams p, double referenceB0Tesla, ProjectNodeId circuitId) {
        return new SimulationConfig(
            p.t1Ms(), p.t2Ms(), p.gamma(),
            p.sliceHalfMm(), p.fovZMm(), p.fovRMm(),
            Math.max(2, p.nZ()), Math.max(2, p.nR()),
            referenceB0Tesla,
            p.dtSeconds(),
            circuitId
        );
    }

    @JsonIgnore
    public double omegaSim() {
        return gamma * referenceB0Tesla;
    }

    @JsonIgnore
    public double larmorHz() {
        return gamma * referenceB0Tesla / (2 * Math.PI);
    }

    @JsonIgnore
    public double nyquistHz() {
        return 1.0 / (2 * dtSeconds);
    }

    public SimulationConfig withT1Ms(double v) {
        return new SimulationConfig(v, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, dtSeconds, circuitId);
    }

    public SimulationConfig withT2Ms(double v) {
        return new SimulationConfig(t1Ms, v, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, dtSeconds, circuitId);
    }

    public SimulationConfig withGamma(double v) {
        return new SimulationConfig(t1Ms, t2Ms, v, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, dtSeconds, circuitId);
    }

    public SimulationConfig withSliceHalfMm(double v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, v, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, dtSeconds, circuitId);
    }

    public SimulationConfig withFovZMm(double v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, v, fovRMm, nZ, nR,
            referenceB0Tesla, dtSeconds, circuitId);
    }

    public SimulationConfig withFovRMm(double v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, v, nZ, nR,
            referenceB0Tesla, dtSeconds, circuitId);
    }

    public SimulationConfig withNZ(int v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, v, nR,
            referenceB0Tesla, dtSeconds, circuitId);
    }

    public SimulationConfig withNR(int v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, v,
            referenceB0Tesla, dtSeconds, circuitId);
    }

    public SimulationConfig withReferenceB0Tesla(double v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            v, dtSeconds, circuitId);
    }

    public SimulationConfig withDtSeconds(double v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, v, circuitId);
    }

    public SimulationConfig withCircuitId(ProjectNodeId v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, dtSeconds, v);
    }
}
