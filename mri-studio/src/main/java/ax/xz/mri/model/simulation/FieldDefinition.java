package ax.xz.mri.model.simulation;

import ax.xz.mri.project.ProjectNodeId;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A magnetic field source in the simulation environment.
 *
 * <p>Each field contributes to the total B-field at every spatial point:
 * <pre>B(r, t) = Σ_i  a_i(t) · E_i(r)</pre>
 * where {@code a_i(t)} is the time-varying amplitude (interpreted as a
 * baseband signal at {@link #carrierHz}) and {@code E_i(r)} is the field's
 * eigenfield (spatial shape at unit amplitude).
 *
 * <p>The number of pulse-sequence control scalars per time step is driven
 * by {@link #kind}: {@code STATIC} = 0, {@code REAL} = 1, {@code QUADRATURE} = 2.
 * Amplitudes carry physical units (Tesla, or T/m for gradients).
 */
public record FieldDefinition(
    String name,
    @JsonProperty("eigenfield_id") ProjectNodeId eigenfieldId,
    AmplitudeKind kind,
    @JsonProperty("carrier_hz") double carrierHz,
    @JsonProperty("min_amplitude") double minAmplitude,
    @JsonProperty("max_amplitude") double maxAmplitude
) {
    public FieldDefinition {
        if (kind == null) kind = AmplitudeKind.REAL;
    }

    @JsonIgnore
    public int channelCount() {
        return kind.channelCount();
    }

    public FieldDefinition withName(String newName) {
        return new FieldDefinition(newName, eigenfieldId, kind, carrierHz, minAmplitude, maxAmplitude);
    }

    public FieldDefinition withKind(AmplitudeKind newKind) {
        return new FieldDefinition(name, eigenfieldId, newKind, carrierHz, minAmplitude, maxAmplitude);
    }

    public FieldDefinition withCarrierHz(double newCarrierHz) {
        return new FieldDefinition(name, eigenfieldId, kind, newCarrierHz, minAmplitude, maxAmplitude);
    }

    public FieldDefinition withMinAmplitude(double newMin) {
        return new FieldDefinition(name, eigenfieldId, kind, carrierHz, newMin, maxAmplitude);
    }

    public FieldDefinition withMaxAmplitude(double newMax) {
        return new FieldDefinition(name, eigenfieldId, kind, carrierHz, minAmplitude, newMax);
    }

    public FieldDefinition withEigenfieldId(ProjectNodeId newId) {
        return new FieldDefinition(name, newId, kind, carrierHz, minAmplitude, maxAmplitude);
    }
}
