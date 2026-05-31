package ax.xz.mri.model.substance.output;

/**
 * Photon emission rate per optical output channel (Hz).
 *
 * <p>Used by NV-style substances: each entry is the instantaneous photon
 * rate (counts / second) on one spectral output channel of the substance
 * (e.g. NV PSB red fluorescence vs. NV ZPL green emission).
 *
 * <p>Unlike {@link MagneticMoment}, optical coupling is <em>explicit</em>:
 * a substance exposes one output port per channel, and only
 * {@link ax.xz.mri.model.probe.OpticalCounter} probes wired to a port
 * see the corresponding rate. The compiler routes by wire connectivity.
 */
public record PhotonClickRate(double[] ratesHz) implements SpinOutput {

    public PhotonClickRate {
        if (ratesHz == null) throw new IllegalArgumentException("ratesHz must be non-null");
        ratesHz = ratesHz.clone();
    }

    public int channelCount() { return ratesHz.length; }
    public double rateHz(int channel) { return ratesHz[channel]; }

    @Override public double[] ratesHz() { return ratesHz.clone(); }
}
