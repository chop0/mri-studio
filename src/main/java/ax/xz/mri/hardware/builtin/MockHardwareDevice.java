package ax.xz.mri.hardware.builtin;

import ax.xz.mri.hardware.HardwareDevice;
import ax.xz.mri.hardware.HardwareException;
import ax.xz.mri.model.scenario.RunResult;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.simulation.MultiProbeSignalTrace;
import ax.xz.mri.model.simulation.SignalTrace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.DoubleConsumer;

/**
 * Synthetic hardware device backing {@link MockHardwarePlugin}.
 *
 * <p>Walks the supplied steps, computes a fake echo response convolved with
 * the transmit channel's amplitude, applies an exponential T₂*-like decay,
 * adds Gaussian noise, and returns the resulting {@link MultiProbeSignalTrace}
 * for the single probe {@link MockHardwarePlugin#PROBE_RX}.
 *
 * <p>The synthetic model is intentionally crude — its job is to verify the
 * run-pipeline plumbing end-to-end (bake → device → result → analysis
 * panes), not to physically simulate anything. Real plugins of course
 * produce real device output.
 */
public final class MockHardwareDevice implements HardwareDevice {

    private final MockHardwareConfig config;
    private boolean closed;

    public MockHardwareDevice(MockHardwareConfig config) {
        this.config = config;
    }

    @Override
    public RunResult.Hardware run(double dtSeconds,
                                  List<SequenceChannel> channelSlots,
                                  List<PulseStep> steps,
                                  DoubleConsumer progress) throws HardwareException {
        if (closed) throw new HardwareException("MockHardwareDevice already closed");
        sleepMs((long) Math.max(0, config.connectionDelayMillis()));

        int totalSteps = steps.size();
        if (totalSteps == 0) {
            return new RunResult.Hardware(List.of(), MultiProbeSignalTrace.empty(),
                Map.of("device", "mock", "samples", "0"));
        }

        int txIndex = indexOf(channelSlots, MockHardwarePlugin.OUT_TX);
        int gxIndex = indexOf(channelSlots, MockHardwarePlugin.OUT_GX);
        int gzIndex = indexOf(channelSlots, MockHardwarePlugin.OUT_GZ);

        int delaySteps = (int) Math.max(0, Math.round(config.echoDelayMicros() * 1e-6 / Math.max(dtSeconds, 1e-15)));
        double decayPerStep = Math.exp(-config.echoDecayHz() * dtSeconds);
        double noiseSigma = Math.max(0, config.noiseLevel());

        var rng = new Random(0x5EED1E5L);
        var iSamples = new ArrayList<Double>(totalSteps);
        var qSamples = new ArrayList<Double>(totalSteps);

        // A toy memory of recent transmit drive — produces an "echo" that
        // appears `delaySteps` later and decays exponentially. Adds a faint
        // gradient feedthrough so non-RF channels also leave a trace.
        double envelope = 0.0;
        for (int i = 0; i < totalSteps; i++) {
            var step = steps.get(i);
            double tx = txIndex >= 0 ? step.control(txIndex) : 0;
            double gx = gxIndex >= 0 ? step.control(gxIndex) : 0;
            double gz = gzIndex >= 0 ? step.control(gzIndex) : 0;

            // Inject the delayed echo from the past tx
            int sourceIdx = i - delaySteps;
            double driveAtSource = sourceIdx >= 0 ? steps.get(sourceIdx).control(Math.max(txIndex, 0)) : 0;
            envelope = envelope * decayPerStep + driveAtSource;

            double iVal = envelope * 0.7 + 0.05 * gx + noiseSigma * rng.nextGaussian();
            double qVal = envelope * 0.3 + 0.05 * gz + noiseSigma * rng.nextGaussian();
            iSamples.add(iVal);
            qSamples.add(qVal);

            if (progress != null && (i & 0xFF) == 0) {
                progress.accept((double) i / totalSteps);
            }
        }
        if (progress != null) progress.accept(1.0);

        var pts = new ArrayList<SignalTrace.Point>(totalSteps);
        double dtMicros = dtSeconds * 1e6;
        for (int i = 0; i < totalSteps; i++) {
            pts.add(new SignalTrace.Point(i * dtMicros, iSamples.get(i), qSamples.get(i)));
        }
        var trace = new SignalTrace(pts);
        var traces = new MultiProbeSignalTrace(
            new java.util.LinkedHashMap<>(Map.of(MockHardwarePlugin.PROBE_RX, trace)),
            MockHardwarePlugin.PROBE_RX);

        return new RunResult.Hardware(
            List.of(),
            traces,
            Map.of(
                "device", "mock",
                "samples", Integer.toString(totalSteps),
                "echo_delay_us", Double.toString(config.echoDelayMicros()),
                "noise_sigma", Double.toString(noiseSigma)
            )
        );
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        sleepMs(Math.min(50, (long) config.connectionDelayMillis() / 4));
    }

    private static int indexOf(List<SequenceChannel> slots, SequenceChannel target) {
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).equals(target)) return i;
        }
        return -1;
    }

    private static void sleepMs(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
