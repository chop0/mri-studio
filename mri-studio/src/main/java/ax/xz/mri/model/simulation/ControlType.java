package ax.xz.mri.model.simulation;

/** How a field source's amplitude is controlled over time. */
public enum ControlType {
	/** On/off — amplitude is either minAmplitude or maxAmplitude. No pulse sequence channel. */
	BINARY,
	/** Continuously variable — amplitude is driven by one or more pulse sequence control channels. */
	LINEAR
}
