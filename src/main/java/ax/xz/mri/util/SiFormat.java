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
        if (!Double.isFinite(value)) return "—";
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

    /**
     * Format a 3-D half-extent (e.g. a substance bounding box) as
     * {@code "±X×±Y×±Z UNIT"} using one SI prefix chosen from the largest
     * extent so all three numbers stay legible.
     */
    public static String fovExtents(double xMetres, double yMetres, double zMetres) {
        double max = Math.max(Math.abs(xMetres), Math.max(Math.abs(yMetres), Math.abs(zMetres)));
        String unit; double scale;
        if (max == 0)         { unit = "m";  scale = 1.0; }
        else if (max < 1e-6)  { unit = "nm"; scale = 1e9; }
        else if (max < 1e-3)  { unit = "μm"; scale = 1e6; }
        else if (max < 1.0)   { unit = "mm"; scale = 1e3; }
        else                  { unit = "m";  scale = 1.0; }
        return String.format("±%.2f × ±%.2f × ±%.2f %s",
            xMetres * scale, yMetres * scale, zMetres * scale, unit);
    }

    /**
     * Pick a single SI prefix for a column of values whose magnitudes vary.
     * Returns a {@code {scale, label}} pair so the same prefix is used for
     * every tick on a chart axis. {@code scale} is the multiplier to convert
     * the base-unit value to the prefixed-unit value; {@code label} is the
     * prefixed unit string like {@code "nT"} or {@code "mm"}.
     */
    public static UnitChoice pickPrefix(double maxAbs, String baseUnit) {
        if (baseUnit == null) baseUnit = "";
        if (!Double.isFinite(maxAbs) || maxAbs == 0) return new UnitChoice(1.0, baseUnit);
        if (maxAbs >= 1e9)  return new UnitChoice(1e-9, "G" + baseUnit);
        if (maxAbs >= 1e6)  return new UnitChoice(1e-6, "M" + baseUnit);
        if (maxAbs >= 1e3)  return new UnitChoice(1e-3, "k" + baseUnit);
        if (maxAbs >= 1)    return new UnitChoice(1.0,  baseUnit);
        if (maxAbs >= 1e-3) return new UnitChoice(1e3,  "m" + baseUnit);
        if (maxAbs >= 1e-6) return new UnitChoice(1e6,  "μ" + baseUnit);
        if (maxAbs >= 1e-9) return new UnitChoice(1e9,  "n" + baseUnit);
        return new UnitChoice(1e12, "p" + baseUnit);
    }

    public record UnitChoice(double scale, String label) {}
}
