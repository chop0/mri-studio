package ax.xz.mri.model.simulation;

import ax.xz.mri.project.ProjectNodeId;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A receive coil — a first-class observer of magnetisation in the FOV.
 *
 * <p>Has an {@link #eigenfieldId() eigenfield} describing spatial sensitivity.
 * By reciprocity, the complex voltage induced in the coil by a transverse
 * magnetisation {@code M⊥(r) = Mₓ(r) + i·M_y(r)} is
 * <pre>
 *   s(t) = gain · e^(i·phaseDeg·π/180) · ∫ (Eₓ(r) − i·E_y(r)) · (Mₓ(r, t) + i·M_y(r, t)) dV
 * </pre>
 *
 * <p>{@link #acquisitionGateName()} (optional) references a
 * {@link AmplitudeKind#GATE GATE} drive path; when that gate is zero the
 * coil reports zero signal (T/R switch in receive-isolation state).
 *
 * <p>{@link #selfInductanceHenry()} is metadata for the future circuit layer
 * (M4/M5) and has no effect on the current integrator.
 */
public record ReceiveCoil(
    String name,
    @JsonProperty("eigenfield_id") ProjectNodeId eigenfieldId,
    double gain,
    @JsonProperty("phase_deg") double phaseDeg,
    @JsonProperty("self_inductance_henry") double selfInductanceHenry,
    @JsonProperty("acquisition_gate_name") String acquisitionGateName
) {
    public ReceiveCoil {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("ReceiveCoil.name must be non-blank");
        if (!Double.isFinite(gain))
            throw new IllegalArgumentException("ReceiveCoil.gain must be finite, got " + gain);
        if (!Double.isFinite(phaseDeg))
            throw new IllegalArgumentException("ReceiveCoil.phaseDeg must be finite, got " + phaseDeg);
        if (!Double.isFinite(selfInductanceHenry) || selfInductanceHenry < 0)
            throw new IllegalArgumentException("ReceiveCoil.selfInductanceHenry must be finite and non-negative, got " + selfInductanceHenry);
    }

    public ReceiveCoil(String name, ProjectNodeId eigenfieldId, double gain, double phaseDeg) {
        this(name, eigenfieldId, gain, phaseDeg, 0.0, null);
    }

    public ReceiveCoil withName(String newName) {
        return new ReceiveCoil(newName, eigenfieldId, gain, phaseDeg, selfInductanceHenry, acquisitionGateName);
    }

    public ReceiveCoil withEigenfieldId(ProjectNodeId newId) {
        return new ReceiveCoil(name, newId, gain, phaseDeg, selfInductanceHenry, acquisitionGateName);
    }

    public ReceiveCoil withGain(double newGain) {
        return new ReceiveCoil(name, eigenfieldId, newGain, phaseDeg, selfInductanceHenry, acquisitionGateName);
    }

    public ReceiveCoil withPhaseDeg(double newPhaseDeg) {
        return new ReceiveCoil(name, eigenfieldId, gain, newPhaseDeg, selfInductanceHenry, acquisitionGateName);
    }

    public ReceiveCoil withSelfInductanceHenry(double newL) {
        return new ReceiveCoil(name, eigenfieldId, gain, phaseDeg, newL, acquisitionGateName);
    }

    public ReceiveCoil withAcquisitionGateName(String newGate) {
        return new ReceiveCoil(name, eigenfieldId, gain, phaseDeg, selfInductanceHenry, newGate);
    }
}
