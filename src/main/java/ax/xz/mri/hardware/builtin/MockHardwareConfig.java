package ax.xz.mri.hardware.builtin;

import ax.xz.mri.hardware.HardwareConfig;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration for the built-in {@link MockHardwarePlugin}.
 *
 * <p>Pure synthetic numbers — there's no real device, no real connection.
 * The fields exist so that the wizard / inspector have something meaningful
 * to render and so users can verify the entire pipeline (wizard → run →
 * traces) without owning hardware.
 *
 * @param noiseLevel       additive Gaussian noise standard deviation, in normalised units
 * @param echoDelayMicros  delay before the synthetic echo appears, in μs
 * @param echoDecayHz      exponential decay rate of the echo envelope (T₂*-like)
 * @param connectionDelayMillis  wall-clock time the {@code connect()} step pretends to take
 */
public record MockHardwareConfig(
    @JsonProperty("noise_level") double noiseLevel,
    @JsonProperty("echo_delay_micros") double echoDelayMicros,
    @JsonProperty("echo_decay_hz") double echoDecayHz,
    @JsonProperty("connection_delay_millis") double connectionDelayMillis
) implements HardwareConfig {

    public static final String PLUGIN_ID = "ax.xz.mri.mock";

    public static MockHardwareConfig defaults() {
        return new MockHardwareConfig(0.01, 200, 50.0, 250);
    }

    @Override
    public String pluginId() { return PLUGIN_ID; }

    public MockHardwareConfig withNoiseLevel(double v)        { return new MockHardwareConfig(v, echoDelayMicros, echoDecayHz, connectionDelayMillis); }
    public MockHardwareConfig withEchoDelayMicros(double v)   { return new MockHardwareConfig(noiseLevel, v, echoDecayHz, connectionDelayMillis); }
    public MockHardwareConfig withEchoDecayHz(double v)       { return new MockHardwareConfig(noiseLevel, echoDelayMicros, v, connectionDelayMillis); }
    public MockHardwareConfig withConnectionDelayMillis(double v) { return new MockHardwareConfig(noiseLevel, echoDelayMicros, echoDecayHz, v); }
}
