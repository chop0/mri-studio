package ax.xz.mri.model.simulation;

/** Preset spatial field configurations for synthetic simulation environments. */
public enum FieldPreset {
    UNIFORM("Uniform", "Constant B0 everywhere, no off-resonance"),
    LINEAR_GRADIENT("Linear Gradient", "Off-resonance varies linearly with z"),
    SINGLE_POINT("Single Point", "Single spatial point — fastest for quick testing");

    private final String displayName;
    private final String description;

    FieldPreset(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() { return displayName; }
    public String description() { return description; }
}
