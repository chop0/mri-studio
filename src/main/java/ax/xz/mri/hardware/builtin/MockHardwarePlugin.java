package ax.xz.mri.hardware.builtin;

import ax.xz.mri.hardware.HardwareConfig;
import ax.xz.mri.hardware.HardwareConfigEditor;
import ax.xz.mri.hardware.HardwareDevice;
import ax.xz.mri.hardware.HardwareException;
import ax.xz.mri.hardware.HardwarePlugin;
import ax.xz.mri.model.sequence.SequenceChannel;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Built-in synthetic hardware plugin. Used for development, demos, and
 * verification that the {@code RunResult.Hardware} pipeline works end to end.
 *
 * <p>Exposes three drive channels and one probe; all configuration is via
 * {@link MockHardwareConfig}. The plugin is registered as a service via
 * {@code module-info.java}'s {@code provides} clause.
 */
public final class MockHardwarePlugin implements HardwarePlugin {

    public static final String ID = MockHardwareConfig.PLUGIN_ID;

    /** Public so other tools (the run-session, tests) can refer to the channel name without hard-coding strings. */
    public static final SequenceChannel OUT_TX = SequenceChannel.of("mock.tx", 0);
    public static final SequenceChannel OUT_GX = SequenceChannel.of("mock.gx", 0);
    public static final SequenceChannel OUT_GZ = SequenceChannel.of("mock.gz", 0);
    public static final String PROBE_RX = "mock.rx";

    private static final Capabilities CAPABILITIES = new Capabilities(
        List.of(OUT_TX, OUT_GX, OUT_GZ),
        List.of(PROBE_RX),
        10_000_000.0,    // 10 MS/s
        1e-7             // 100 ns minimum dt
    );

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Mock Device"; }
    @Override public String description() {
        return "Synthetic device for testing the run pipeline. "
            + "Echoes the transmit channel back as a damped sinusoid plus Gaussian noise.";
    }
    @Override public Capabilities capabilities() { return CAPABILITIES; }
    @Override public HardwareConfig defaultConfig() { return MockHardwareConfig.defaults(); }

    @Override
    public HardwareConfigEditor configEditor(HardwareConfig initial) {
        return new MockHardwareConfigEditor(initial instanceof MockHardwareConfig mc ? mc : MockHardwareConfig.defaults());
    }

    @Override
    public HardwareDevice open(HardwareConfig config) throws HardwareException {
        if (!(config instanceof MockHardwareConfig mc))
            throw new HardwareException("MockHardwarePlugin received a non-mock config: " + config);
        return new MockHardwareDevice(mc);
    }

    @Override
    public HardwareConfig deserialiseConfig(JsonNode raw, ObjectMapper mapper) throws JsonMappingException {
        if (raw == null || raw.isNull()) return defaultConfig();
        return mapper.convertValue(raw, MockHardwareConfig.class);
    }
}
