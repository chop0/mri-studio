package ax.xz.mri.util;

/**
 * Centralised SI-prefix formatters for displayed quantities.
 *
 * <p>Pane code historically had its own copy of {@code formatTime}, {@code
 * formatHz}, {@code formatAmps}, etc. — keeping them here ensures one
 * consistent rendering across the studio.
 */
public final class SiFormat {
    private SiFormat() {}

    /** Format a duration given in microseconds as μs / ms / s with 2-decimal precision. */
    public static String time(double micros) {
        double abs = Math.abs(micros);
        if (abs >= 1_000_000) return String.format("%.2f s",  micros * 1e-6);
        if (abs >= 1_000)     return String.format("%.2f ms", micros * 1e-3);
        return String.format("%.0f μs", micros);
    }

    /** Format a frequency (Hz) as Hz / kHz / MHz / GHz; "DC" for exactly zero. */
    public static String hz(double hz) {
        double abs = Math.abs(hz);
        if (abs == 0)         return "DC";
        if (abs >= 1e9)       return String.format("%.3f GHz", hz / 1e9);
        if (abs >= 1e6)       return String.format("%.3f MHz", hz / 1e6);
        if (abs >= 1e3)       return String.format("%.3f kHz", hz / 1e3);
        return String.format("%.3f Hz", hz);
    }

    /** Format a current (A) as A / mA / µA. */
    public static String amps(double a) {
        double abs = Math.abs(a);
        if (abs == 0)         return "0 A";
        if (abs >= 1)         return String.format("%.3f A",  a);
        if (abs >= 1e-3)      return String.format("%.2f mA", a * 1e3);
        if (abs >= 1e-6)      return String.format("%.2f µA", a * 1e6);
        return String.format("%.3g A", a);
    }

    /** Format a Tesla-per-amp ratio as T/A / mT/A / µT/A. */
    public static String teslaPerAmp(double tpa) {
        double abs = Math.abs(tpa);
        if (abs == 0)         return "0 T/A";
        if (abs >= 1)         return String.format("%.3f T/A",  tpa);
        if (abs >= 1e-3)      return String.format("%.2f mT/A", tpa * 1e3);
        if (abs >= 1e-6)      return String.format("%.2f µT/A", tpa * 1e6);
        return String.format("%.3g T/A", tpa);
    }

    /** Format a value with arbitrary SI units, sweeping from giga down to nano. */
    public static String si(double value, String units) {
        if (units == null || units.isEmpty()) return String.format("%.3g", value);
        double abs = Math.abs(value);
        if (abs == 0)         return "0 " + units;
        if (abs >= 1e9)       return String.format("%.2f G%s", value / 1e9, units);
        if (abs >= 1e6)       return String.format("%.2f M%s", value / 1e6, units);
        if (abs >= 1e3)       return String.format("%.2f k%s", value / 1e3, units);
        if (abs >= 1)         return String.format("%.2f %s",  value,       units);
        if (abs >= 1e-3)      return String.format("%.2f m%s", value * 1e3, units);
        if (abs >= 1e-6)      return String.format("%.2f μ%s", value * 1e6, units);
        if (abs >= 1e-9)      return String.format("%.2f n%s", value * 1e9, units);
        return String.format("%.3g %s", value, units);
    }
}
