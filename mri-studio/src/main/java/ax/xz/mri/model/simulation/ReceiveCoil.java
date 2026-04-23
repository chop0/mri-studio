package ax.xz.mri.model.simulation;

import ax.xz.mri.project.ProjectNodeId;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A receive coil — a first-class observer of magnetisation in the FOV.
 *
 * <p>A receive coil has an {@link #eigenfieldId() eigenfield} describing its
 * spatial sensitivity (the {@code B₁} pattern the coil would produce if driven
 * with unit current). By reciprocity, the complex voltage induced in the coil
 * by a transverse magnetisation {@code M⊥(r) = Mₓ(r) + i·M_y(r)} is
 * <pre>
 *   s(t) = gain · e^(i·phaseDeg·π/180) · ∫ (Eₓ(r) − i·E_y(r)) · (Mₓ(r, t) + i·M_y(r, t)) dV
 * </pre>
 *
 * <p>A receive coil does not contribute to the Bloch integration — it only
 * observes. It is disjoint from {@link FieldDefinition}, which represents a
 * driven field source.
 *
 * <p>Future milestones will wire a receive coil into a circuit schematic with
 * lumped impedances, switches, and probe voltages; at that point {@code gain}
 * and {@code phaseDeg} become emergent from the network rather than
 * user-edited scalars.
 */
public record ReceiveCoil(
    String name,
    @JsonProperty("eigenfield_id") ProjectNodeId eigenfieldId,
    double gain,
    @JsonProperty("phase_deg") double phaseDeg
) {
    public ReceiveCoil {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("ReceiveCoil.name must be non-blank");
        if (!Double.isFinite(gain))
            throw new IllegalArgumentException("ReceiveCoil.gain must be finite, got " + gain);
        if (!Double.isFinite(phaseDeg))
            throw new IllegalArgumentException("ReceiveCoil.phaseDeg must be finite, got " + phaseDeg);
    }

    public ReceiveCoil withName(String newName) {
        return new ReceiveCoil(newName, eigenfieldId, gain, phaseDeg);
    }

    public ReceiveCoil withEigenfieldId(ProjectNodeId newId) {
        return new ReceiveCoil(name, newId, gain, phaseDeg);
    }

    public ReceiveCoil withGain(double newGain) {
        return new ReceiveCoil(name, eigenfieldId, newGain, phaseDeg);
    }

    public ReceiveCoil withPhaseDeg(double newPhaseDeg) {
        return new ReceiveCoil(name, eigenfieldId, gain, newPhaseDeg);
    }
}
