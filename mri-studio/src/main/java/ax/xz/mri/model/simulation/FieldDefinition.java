package ax.xz.mri.model.simulation;

import ax.xz.mri.project.ProjectNodeId;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A magnetic field source in the simulation environment.
 *
 * <p>Each field contributes to the total B-field at every spatial point:
 * <pre>B(r, t) = Σ_i  a_i(t) · E_i(r)</pre>
 * where {@code a_i(t)} is the time-varying amplitude and {@code E_i(r)} is the
 * eigenfield (spatial shape at unit amplitude).
 *
 * <p>Amplitudes carry physical units (Tesla for B0/RF, T/m for gradients).
 * The eigenfield is normalised to unit peak; amplitude scales it.
 *
 * <p>Baseband frequency determines control channel count:
 * <ul>
 *   <li>DC (0 Hz) → 1 control channel (scalar amplitude)</li>
 *   <li>Non-DC → 2 control channels (I/Q quadrature)</li>
 * </ul>
 */
public record FieldDefinition(
	String name,
	@JsonProperty("control_type") ControlType controlType,
	@JsonProperty("min_amplitude") double minAmplitude,
	@JsonProperty("max_amplitude") double maxAmplitude,
	@JsonProperty("baseband_frequency_hz") double basebandFrequencyHz,
	@JsonProperty("eigenfield_id") ProjectNodeId eigenfieldId
) {
	/** True if this field operates at DC (baseband = 0 Hz). */
	public boolean isDC() {
		return basebandFrequencyHz == 0.0;
	}

	/** Number of pulse-sequence control channels this field requires. */
	public int controlChannelCount() {
		if (controlType == ControlType.BINARY) return 0;
		return isDC() ? 1 : 2;
	}

	public FieldDefinition withName(String newName) {
		return new FieldDefinition(newName, controlType, minAmplitude, maxAmplitude, basebandFrequencyHz, eigenfieldId);
	}

	public FieldDefinition withControlType(ControlType newControlType) {
		return new FieldDefinition(name, newControlType, minAmplitude, maxAmplitude, basebandFrequencyHz, eigenfieldId);
	}

	public FieldDefinition withMinAmplitude(double newMin) {
		return new FieldDefinition(name, controlType, newMin, maxAmplitude, basebandFrequencyHz, eigenfieldId);
	}

	public FieldDefinition withMaxAmplitude(double newMax) {
		return new FieldDefinition(name, controlType, minAmplitude, newMax, basebandFrequencyHz, eigenfieldId);
	}

	public FieldDefinition withBasebandFrequencyHz(double newFreq) {
		return new FieldDefinition(name, controlType, minAmplitude, maxAmplitude, newFreq, eigenfieldId);
	}

	public FieldDefinition withEigenfieldId(ProjectNodeId newId) {
		return new FieldDefinition(name, controlType, minAmplitude, maxAmplitude, basebandFrequencyHz, newId);
	}
}
