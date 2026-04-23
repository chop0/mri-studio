package ax.xz.mri.model.simulation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * User-editable simulation environment configuration.
 *
 * <h3>Shape</h3>
 * <ul>
 *   <li>Tissue / nucleus: {@code t1Ms}, {@code t2Ms}, {@code gamma}.</li>
 *   <li>Spatial grid: {@code sliceHalfMm}, {@code fovZMm}, {@code fovRMm},
 *       {@code nZ}, {@code nR}. Each eigenfield's declared
 *       {@link FieldSymmetry} determines whether the factory samples on the
 *       2D cylindrical or 3D Cartesian view of this grid.</li>
 *   <li>Rotating frame: {@code referenceB0Tesla} → {@code ω_s = γ · B0ref}.</li>
 *   <li>{@code dtSeconds}: integration step.</li>
 *   <li>{@link TransmitCoil}s: physical coils that generate B-fields.</li>
 *   <li>{@link DrivePath}s: named routes from the DAW timeline into coils
 *       (or into gate lines). Drive paths with {@link AmplitudeKind#GATE} do
 *       not reference a coil — they emit digital signals that gate other
 *       paths or receive coils.</li>
 *   <li>{@link ReceiveCoil}s: observers. Their
 *       {@link ReceiveCoil#acquisitionGateName() acquisition gate} (optional)
 *       references a gate drive path.</li>
 * </ul>
 *
 * <p>A {@code BlochDataFactory} converts this into a runtime
 * {@link ax.xz.mri.model.scenario.BlochData}.
 */
public record SimulationConfig(
    @JsonProperty("t1_ms") double t1Ms,
    @JsonProperty("t2_ms") double t2Ms,
    double gamma,

    @JsonProperty("slice_half_mm") double sliceHalfMm,
    @JsonProperty("fov_z_mm") double fovZMm,
    @JsonProperty("fov_r_mm") double fovRMm,
    @JsonProperty("n_z") int nZ,
    @JsonProperty("n_r") int nR,

    @JsonProperty("reference_b0_tesla") double referenceB0Tesla,

    @JsonProperty("dt_seconds") double dtSeconds,

    @JsonProperty("transmit_coils") List<TransmitCoil> transmitCoils,
    @JsonProperty("drive_paths") List<DrivePath> drivePaths,
    @JsonProperty("receive_coils") List<ReceiveCoil> receiveCoils
) {
    public SimulationConfig {
        transmitCoils = transmitCoils == null ? List.of() : List.copyOf(transmitCoils);
        drivePaths = drivePaths == null ? List.of() : List.copyOf(drivePaths);
        receiveCoils = receiveCoils == null ? List.of() : List.copyOf(receiveCoils);
        if (!(dtSeconds > 0) || !Double.isFinite(dtSeconds)) {
            throw new IllegalArgumentException("dtSeconds must be a finite positive value, got " + dtSeconds);
        }
        validateReferences(transmitCoils, drivePaths);
    }

    private static void validateReferences(List<TransmitCoil> coils, List<DrivePath> paths) {
        var coilNames = new java.util.HashSet<String>();
        for (var c : coils) coilNames.add(c.name());
        var pathNames = new java.util.HashSet<String>();
        for (var p : paths) {
            if (!pathNames.add(p.name()))
                throw new IllegalArgumentException("Duplicate drive-path name: " + p.name());
            if (!p.isGate()) {
                if (!coilNames.contains(p.transmitCoilName()))
                    throw new IllegalArgumentException("DrivePath '" + p.name() + "' references unknown transmit coil '" + p.transmitCoilName() + "'");
            }
        }
        // Gate inputs must reference existing paths; duplicate names already rejected above.
        for (var p : paths) {
            if (p.gateInputName() != null && !pathNames.contains(p.gateInputName()))
                throw new IllegalArgumentException("DrivePath '" + p.name() + "' gate-input '" + p.gateInputName() + "' is not a drive path");
        }
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

    @JsonIgnore
    public int totalChannelCount() {
        int total = 0;
        for (var p : drivePaths) total += p.channelCount();
        return total;
    }

    public SimulationConfig withT1Ms(double v) {
        return new SimulationConfig(v, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, dtSeconds, transmitCoils, drivePaths, receiveCoils);
    }

    public SimulationConfig withT2Ms(double v) {
        return new SimulationConfig(t1Ms, v, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, dtSeconds, transmitCoils, drivePaths, receiveCoils);
    }

    public SimulationConfig withGamma(double v) {
        return new SimulationConfig(t1Ms, t2Ms, v, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, dtSeconds, transmitCoils, drivePaths, receiveCoils);
    }

    public SimulationConfig withSliceHalfMm(double v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, v, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, dtSeconds, transmitCoils, drivePaths, receiveCoils);
    }

    public SimulationConfig withFovZMm(double v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, v, fovRMm, nZ, nR,
            referenceB0Tesla, dtSeconds, transmitCoils, drivePaths, receiveCoils);
    }

    public SimulationConfig withFovRMm(double v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, v, nZ, nR,
            referenceB0Tesla, dtSeconds, transmitCoils, drivePaths, receiveCoils);
    }

    public SimulationConfig withNZ(int v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, v, nR,
            referenceB0Tesla, dtSeconds, transmitCoils, drivePaths, receiveCoils);
    }

    public SimulationConfig withNR(int v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, v,
            referenceB0Tesla, dtSeconds, transmitCoils, drivePaths, receiveCoils);
    }

    public SimulationConfig withReferenceB0Tesla(double v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            v, dtSeconds, transmitCoils, drivePaths, receiveCoils);
    }

    public SimulationConfig withDtSeconds(double v) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, v, transmitCoils, drivePaths, receiveCoils);
    }

    public SimulationConfig withTransmitCoils(List<TransmitCoil> newCoils) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, dtSeconds, newCoils, drivePaths, receiveCoils);
    }

    public SimulationConfig withDrivePaths(List<DrivePath> newPaths) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, dtSeconds, transmitCoils, newPaths, receiveCoils);
    }

    public SimulationConfig withReceiveCoils(List<ReceiveCoil> newReceiveCoils) {
        return new SimulationConfig(t1Ms, t2Ms, gamma, sliceHalfMm, fovZMm, fovRMm, nZ, nR,
            referenceB0Tesla, dtSeconds, transmitCoils, drivePaths, newReceiveCoils);
    }
}
