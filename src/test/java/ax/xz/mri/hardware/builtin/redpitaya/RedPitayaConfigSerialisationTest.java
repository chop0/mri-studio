package ax.xz.mri.hardware.builtin.redpitaya;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedPitayaConfigSerialisationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RedPitayaPlugin plugin = new RedPitayaPlugin();

    @Test
    void defaultConfigRoundTripsThroughJson() throws Exception {
        var original = (RedPitayaConfig) plugin.defaultConfig();
        String json = mapper.writeValueAsString(original);
        var restored = mapper.readValue(json, RedPitayaConfig.class);
        assertEquals(original, restored);
    }

    @Test
    void fullyPopulatedConfigRoundTripsThroughJson() throws Exception {
        var labels = new LinkedHashMap<RedPitayaChannel, String>();
        labels.put(RedPitayaChannel.DIO0_P, "B0 enable");
        labels.put(RedPitayaChannel.DIO3_P, "TR switch");
        labels.put(RedPitayaChannel.DIO7_N, "Spare");

        var cfg = new RedPitayaConfig(
            "rp-f03e18.local", 6981, 1500,
            21.3e6, 21.4e6,
            RedPitayaSampleRate.DECIM_16,
            RedPitayaTxPort.OUT2,
            0.42,
            RedPitayaChannel.DIO0_P,
            labels,
            5000
        );
        String json = mapper.writeValueAsString(cfg);
        var restored = mapper.readValue(json, RedPitayaConfig.class);
        assertEquals(cfg, restored);
        assertEquals("B0 enable", restored.pinLabels().get(RedPitayaChannel.DIO0_P));
    }

    @Test
    void deserialiseConfigUsesPluginPath() throws Exception {
        var cfg = (RedPitayaConfig) plugin.defaultConfig();
        var node = mapper.valueToTree(cfg);
        var restored = (RedPitayaConfig) plugin.deserialiseConfig(node, mapper);
        assertEquals(cfg, restored);
    }

    @Test
    void pluginIdIsStableAndDescriptive() {
        assertEquals("ax.xz.mri.redpitaya.stockos", RedPitayaConfig.PLUGIN_ID);
        assertEquals(RedPitayaConfig.PLUGIN_ID, plugin.id());
        assertNotNull(plugin.displayName());
        assertNotNull(plugin.description());
        assertTrue(plugin.description().toLowerCase().contains("red pitaya"));
    }
}
