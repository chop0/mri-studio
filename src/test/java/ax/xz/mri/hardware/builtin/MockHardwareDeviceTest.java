package ax.xz.mri.hardware.builtin;

import ax.xz.mri.model.scenario.RunResult;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.SequenceChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end sanity for {@link MockHardwareDevice} — given a baked
 * {@link PulseStep} list it returns a {@link RunResult.Hardware} with one
 * probe trace whose sample count matches the step count.
 */
class MockHardwareDeviceTest {

    @Test
    void runEchoesInputIntoProbeTrace() throws Exception {
        var config = new MockHardwareConfig(0.0, 50, 100, 0);   // no noise, no connect delay
        var plugin = new MockHardwarePlugin();
        var channels = plugin.capabilities().outputChannels();

        var steps = new ArrayList<PulseStep>();
        for (int i = 0; i < 200; i++) {
            // Drive the TX channel with a unit pulse for the first 20 steps.
            double tx = i < 20 ? 1.0 : 0.0;
            double[] controls = new double[channels.size()];
            int txIdx = channels.indexOf(MockHardwarePlugin.OUT_TX);
            controls[txIdx] = tx;
            steps.add(new PulseStep(controls, tx > 0 ? 1.0 : 0.0));
        }

        try (var device = plugin.open(config)) {
            var result = device.run(1e-6, channels, steps, null);
            assertNotNull(result);
            assertNotNull(result.probeTraces());
            var primary = result.probeTraces().primary();
            assertNotNull(primary, "Mock device must emit a primary probe trace");
            assertEquals(steps.size(), primary.points().size());
            // Quietly assert that the trace is non-trivial — at least one sample
            // beyond step 50 (i.e. after the echo delay) should be non-zero.
            boolean anyEcho = primary.points().stream()
                .skip(60)
                .anyMatch(p -> Math.abs(p.real()) > 1e-6);
            assertTrue(anyEcho, "Expected an echo response after the configured delay");
        }
    }

    @Test
    void emptyStepsListProducesEmptyResult() throws Exception {
        var plugin = new MockHardwarePlugin();
        var config = (MockHardwareConfig) plugin.defaultConfig();
        try (var device = plugin.open(config)) {
            var result = device.run(1e-6, plugin.capabilities().outputChannels(), List.of(), null);
            assertEquals(0, result.probeTraces().byProbe().getOrDefault(MockHardwarePlugin.PROBE_RX,
                ax.xz.mri.model.simulation.MultiProbeSignalTrace.empty().byProbe()
                    .getOrDefault(MockHardwarePlugin.PROBE_RX, null) == null
                    ? new ax.xz.mri.model.simulation.SignalTrace(List.of())
                    : new ax.xz.mri.model.simulation.SignalTrace(List.of())).points().size());
        }
    }

    @Test
    void unusedChannelLookupHandlesMissingTx() throws Exception {
        // If TX channel isn't in the slot list the device shouldn't crash —
        // it just returns a trace dominated by noise / zero.
        var plugin = new MockHardwarePlugin();
        var config = new MockHardwareConfig(0.0, 100, 100, 0);
        var slots = List.of(SequenceChannel.of("not.tx", 0));
        var steps = List.of(new PulseStep(new double[]{0.0}, 0.0), new PulseStep(new double[]{0.0}, 0.0));
        try (var device = plugin.open(config)) {
            var result = device.run(1e-6, slots, steps, null);
            assertEquals(steps.size(), result.probeTraces().primary().points().size());
        }
    }
}
