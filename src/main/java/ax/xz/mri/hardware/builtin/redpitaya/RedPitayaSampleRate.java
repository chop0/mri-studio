package ax.xz.mri.hardware.builtin.redpitaya;

/**
 * Decimation factors supported by librp's ADC/DAC streaming on the
 * STEMlab 125-14 (125 MS/s base clock). The effective baseband I/Q rate is
 * {@code 125_000_000 / decimation}.
 *
 * <p>The set mirrors librp's accepted factors. Adding a new factor is one
 * new constant; the proto schema and C server already accept the raw uint32.
 */
public enum RedPitayaSampleRate {
    DECIM_1(1),
    DECIM_2(2),
    DECIM_4(4),
    DECIM_8(8),
    DECIM_16(16),
    DECIM_32(32),
    DECIM_64(64),
    DECIM_128(128),
    DECIM_256(256),
    DECIM_512(512),
    DECIM_1024(1024),
    DECIM_8192(8192),
    DECIM_65536(65536);

    public static final double BASE_CLOCK_HZ = 125_000_000.0;

    private final int decimation;

    RedPitayaSampleRate(int decimation) {
        this.decimation = decimation;
    }

    public int decimation() {
        return decimation;
    }

    public double effectiveRateHz() {
        return BASE_CLOCK_HZ / decimation;
    }

    public double minDtSeconds() {
        return 1.0 / effectiveRateHz();
    }

    public double maxBandwidthHz() {
        return effectiveRateHz() * 0.5;
    }

    public String label() {
        double rate = effectiveRateHz();
        if (rate >= 1e6) return String.format("%.3f MS/s (decim %d)", rate * 1e-6, decimation);
        if (rate >= 1e3) return String.format("%.3f kS/s (decim %d)", rate * 1e-3, decimation);
        return String.format("%.0f S/s (decim %d)", rate, decimation);
    }
}
