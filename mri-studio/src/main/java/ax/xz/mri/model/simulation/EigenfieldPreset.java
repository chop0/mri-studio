package ax.xz.mri.model.simulation;

/**
 * Analytical generation method for eigenfield spatial data.
 *
 * <p>Each preset corresponds to a coil geometry or idealised field shape.
 * {@link BlochDataFactory} uses the preset to compute the spatial field
 * contribution on the simulation grid. When the eigenfield editor is built,
 * presets will be replaceable with discrete spatial data.
 */
public enum EigenfieldPreset {
	/** Constant z-directed field everywhere (ideal B0). */
	UNIFORM_BZ("Uniform Bz", "Perfectly homogeneous z-directed field"),

	/** B0 from a modified Helmholtz coil pair, computed via Biot-Savart. */
	BIOT_SAVART_HELMHOLTZ("Helmholtz B0", "Realistic B0 from Helmholtz-like coils with spatial inhomogeneity"),

	/** Ideal linear x-gradient (Bz varies linearly with x). */
	IDEAL_GRADIENT_X("Gradient X", "Linear x-gradient of Bz"),

	/** Ideal linear z-gradient (Bz varies linearly with z). */
	IDEAL_GRADIENT_Z("Gradient Z", "Linear z-gradient of Bz"),

	/** Uniform transverse field (ideal RF B1 coil). */
	UNIFORM_B_PERP("Uniform B⊥", "Perfectly uniform transverse B1 field");

	private final String displayName;
	private final String description;

	EigenfieldPreset(String displayName, String description) {
		this.displayName = displayName;
		this.description = description;
	}

	public String displayName() { return displayName; }
	public String description() { return description; }
}
