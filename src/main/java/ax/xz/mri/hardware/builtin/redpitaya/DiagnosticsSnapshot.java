package ax.xz.mri.hardware.builtin.redpitaya;

import ax.xz.mri.hardware.builtin.redpitaya.proto.DeviceFact;
import ax.xz.mri.hardware.builtin.redpitaya.proto.DiagReply;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable snapshot of one diagnostics poll. Fields known to v1 of the
 * server are pulled into typed members; anything else lands in
 * {@link #extras} so future server keys appear in the diagnostics UI
 * without a Java change.
 */
public record DiagnosticsSnapshot(
    Instant pollTime,
    boolean reachable,
    double rttMs,
    String model,
    String mac,
    String kernel,
    long uptimeSeconds,
    double cpuTempCelsius,
    long linkSpeedMbps,
    long adcOverflowsSinceBoot,
    long dacUnderrunsSinceBoot,
    String serverGitSha,
    Map<String, String> extras
) {
    public static DiagnosticsSnapshot unreachable(Instant pollTime, double rttMs) {
        return new DiagnosticsSnapshot(
            pollTime, false, rttMs,
            "", "", "", 0L, Double.NaN, 0L, 0L, 0L, "",
            Map.of()
        );
    }

    public static DiagnosticsSnapshot from(Instant pollTime, double rttMs,
                                           DiagReply reply, String serverGitSha) {
        var extras = new LinkedHashMap<String, String>();
        String model = "", mac = "", kernel = "";
        long uptime = 0L, link = 0L, adcOver = 0L, dacUnder = 0L;
        double cpuTemp = Double.NaN;

        for (DeviceFact f : reply.getFactsList()) {
            String s = stringValue(f);
            switch (f.getKey()) {
                case "model"                     -> model = s;
                case "mac"                       -> mac = s;
                case "kernel"                    -> kernel = s;
                case "uptime_s"                  -> uptime = longValue(f);
                case "cpu_temp_c"                -> cpuTemp = doubleValue(f);
                case "link_speed_mbps"           -> link = longValue(f);
                case "adc_overflows_since_boot"  -> adcOver = longValue(f);
                case "dac_underruns_since_boot"  -> dacUnder = longValue(f);
                default                          -> extras.put(f.getKey(), s);
            }
        }
        return new DiagnosticsSnapshot(
            pollTime, true, rttMs,
            model, mac, kernel, uptime, cpuTemp, link, adcOver, dacUnder, serverGitSha,
            Map.copyOf(extras)
        );
    }

    private static String stringValue(DeviceFact f) {
        var v = f.getValue();
        return switch (v.getVCase()) {
            case B -> Boolean.toString(v.getB());
            case I -> Long.toString(v.getI());
            case D -> Double.toString(v.getD());
            case S -> v.getS();
            case RAW -> "<" + v.getRaw().size() + " bytes>";
            case V_NOT_SET -> "";
        };
    }

    private static long longValue(DeviceFact f) {
        var v = f.getValue();
        return switch (v.getVCase()) {
            case I -> v.getI();
            case D -> (long) v.getD();
            case S -> { try { yield Long.parseLong(v.getS().trim()); } catch (NumberFormatException e) { yield 0L; } }
            default -> 0L;
        };
    }

    private static double doubleValue(DeviceFact f) {
        var v = f.getValue();
        return switch (v.getVCase()) {
            case D -> v.getD();
            case I -> v.getI();
            case S -> { try { yield Double.parseDouble(v.getS().trim()); } catch (NumberFormatException e) { yield Double.NaN; } }
            default -> Double.NaN;
        };
    }
}
