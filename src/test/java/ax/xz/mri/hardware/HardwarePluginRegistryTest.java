package ax.xz.mri.hardware;

import ax.xz.mri.hardware.builtin.MockHardwarePlugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sanity checks for the {@link HardwarePluginRegistry}: it must discover the
 * built-in {@link MockHardwarePlugin} via {@link java.util.ServiceLoader}
 * (registered through {@code module-info.java}'s {@code provides} clause).
 */
class HardwarePluginRegistryTest {

    @Test
    void mockPluginIsDiscoveredByServiceLoader() {
        var found = HardwarePluginRegistry.byId(MockHardwarePlugin.ID);
        assertTrue(found.isPresent(), "MockHardwarePlugin must be registered as a service provider");
        assertEquals("Mock Device", found.get().displayName());
    }

    @Test
    void capabilitiesExposeOutputsAndProbes() {
        var plugin = HardwarePluginRegistry.byId(MockHardwarePlugin.ID).orElseThrow();
        var caps = plugin.capabilities();
        assertFalse(caps.outputChannels().isEmpty(), "Mock plugin must advertise output channels");
        assertFalse(caps.probeNames().isEmpty(), "Mock plugin must advertise at least one probe");
        assertTrue(caps.maxSampleRateHz() > 0);
    }

    @Test
    void defaultConfigRoundTripsThroughEnvelope() throws Exception {
        var plugin = HardwarePluginRegistry.byId(MockHardwarePlugin.ID).orElseThrow();
        var defaults = plugin.defaultConfig();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var envelope = HardwareConfigEnvelope.wrap(defaults, mapper);
        var resolved = envelope.unwrap(mapper);
        assertEquals(defaults, resolved);
    }
}
