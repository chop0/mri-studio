package ax.xz.mri.hardware;

import ax.xz.mri.model.scenario.RunResult;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.SequenceChannel;

import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * Live handle to a hardware device, opened by {@link HardwarePlugin#open}.
 *
 * <p>The device is responsible for connecting, validating that the requested
 * channel layout is achievable, executing the baked sequence, gathering ADC
 * data, and returning a {@link RunResult.Hardware} for the analysis panes.
 * Implementations should be cheap to construct (real I/O happens lazily, in
 * {@link #run}) but expensive to keep open — callers wrap usage in
 * try-with-resources.
 */
public interface HardwareDevice extends AutoCloseable {

    /**
     * Execute a baked sequence on the device.
     *
     * @param dtSeconds   simulator-frame time step that the steps' controls were sampled at
     * @param channelSlots ordered slot list — the {@code i}-th entry of each
     *                     {@link PulseStep#controls()} array is destined for
     *                     {@code channelSlots[i]}; the device validates that
     *                     these are a subset of the plugin's
     *                     {@link HardwarePlugin.Capabilities#outputChannels()}
     * @param steps       per-time-step control values
     * @param progress    optional progress callback {@code [0, 1]} on the device's I/O thread;
     *                    callers usually marshal into the FX thread before touching the UI
     */
    RunResult.Hardware run(double dtSeconds,
                            List<SequenceChannel> channelSlots,
                            List<PulseStep> steps,
                            DoubleConsumer progress) throws HardwareException;

    @Override void close();
}
