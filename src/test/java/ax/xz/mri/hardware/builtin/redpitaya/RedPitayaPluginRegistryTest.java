package ax.xz.mri.hardware.builtin.redpitaya;

import ax.xz.mri.hardware.HardwarePluginRegistry;
import ax.xz.mri.model.sequence.SequenceChannel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedPitayaPluginRegistryTest {

    @BeforeAll
    static void refreshRegistry() {
        HardwarePluginRegistry.refresh();
    }

    @Test
    void pluginIsDiscoverableViaServiceLoader() {
        var plugin = HardwarePluginRegistry.byId(RedPitayaConfig.PLUGIN_ID);
        assertTrue(plugin.isPresent(), "RedPitayaPlugin should be registered via ServiceLoader");
        assertNotNull(plugin.get().displayName());
    }

    @Test
    void capabilitiesExposeAll18ChannelsAnd1Probe() {
        var plugin = HardwarePluginRegistry.byId(RedPitayaConfig.PLUGIN_ID).orElseThrow();
        var caps = plugin.capabilities();

        List<SequenceChannel> outs = caps.outputChannels();
        assertEquals(18, outs.size(), "expect TX I + TX Q + 16 DIO pins");
        assertTrue(outs.contains(SequenceChannel.of("rp.tx.i", 0)), "TX I missing");
        assertTrue(outs.contains(SequenceChannel.of("rp.tx.q", 0)), "TX Q missing");
        for (int n = 0; n < 8; n++) {
            assertTrue(outs.contains(SequenceChannel.of("rp.dio." + n + "_p", 0)), "DIO" + n + "_P missing");
            assertTrue(outs.contains(SequenceChannel.of("rp.dio." + n + "_n", 0)), "DIO" + n + "_N missing");
        }

        assertEquals(
            List.of(RedPitayaPlugin.PROBE_RX, RedPitayaPlugin.PROBE_RX_I, RedPitayaPlugin.PROBE_RX_Q),
            caps.probeNames(),
            "expect raw ADC + DDC in-phase + DDC quadrature");
    }
}
