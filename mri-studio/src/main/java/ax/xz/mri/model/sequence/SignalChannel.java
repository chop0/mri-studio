package ax.xz.mri.model.sequence;

/** The five editable waveform channels in a pulse sequence. */
public enum SignalChannel {
    B1X("B₁x", "μT"),
    B1Y("B₁y", "μT"),
    GX("Gx", "mT/m"),
    GZ("Gz", "mT/m"),
    RF_GATE("RF Gate", "");

    private final String label;
    private final String unit;

    SignalChannel(String label, String unit) {
        this.label = label;
        this.unit = unit;
    }

    public String label() { return label; }
    public String unit()  { return unit; }
}
