package ax.xz.mri.model.simulation;

import ax.xz.mri.project.ProjectNodeId;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A transmit coil — the physical object that produces a B-field in the FOV
 * when current flows through it.
 *
 * <p>A {@link TransmitCoil} pairs a {@link #name() name} (which {@link DrivePath}s
 * reference) with a spatial {@link #eigenfieldId() eigenfield} describing the
 * B-field shape at unit current. {@link #selfInductance()} is metadata for the
 * future circuit-level layer (M4/M5) and has no effect on the Bloch simulator
 * today.
 *
 * <p>Disjoint from {@link DrivePath}: the coil is "what the hardware is," the
 * drive path is "how the sequence talks to it."
 */
public record TransmitCoil(
    String name,
    @JsonProperty("eigenfield_id") ProjectNodeId eigenfieldId,
    @JsonProperty("self_inductance_henry") double selfInductanceHenry
) {
    public TransmitCoil {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("TransmitCoil.name must be non-blank");
        if (!Double.isFinite(selfInductanceHenry) || selfInductanceHenry < 0)
            throw new IllegalArgumentException("TransmitCoil.selfInductanceHenry must be finite and non-negative, got " + selfInductanceHenry);
    }

    public TransmitCoil withName(String newName) {
        return new TransmitCoil(newName, eigenfieldId, selfInductanceHenry);
    }

    public TransmitCoil withEigenfieldId(ProjectNodeId newId) {
        return new TransmitCoil(name, newId, selfInductanceHenry);
    }

    public TransmitCoil withSelfInductanceHenry(double newL) {
        return new TransmitCoil(name, eigenfieldId, newL);
    }
}
