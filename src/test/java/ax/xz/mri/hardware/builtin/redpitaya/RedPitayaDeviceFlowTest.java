package ax.xz.mri.hardware.builtin.redpitaya;

import ax.xz.mri.hardware.HardwareException;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Ack;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Command;
import ax.xz.mri.hardware.builtin.redpitaya.proto.HelloAck;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Metric;
import ax.xz.mri.hardware.builtin.redpitaya.proto.ParamValue;
import ax.xz.mri.hardware.builtin.redpitaya.proto.PinId;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Reply;
import ax.xz.mri.hardware.builtin.redpitaya.proto.RunResult;
import ax.xz.mri.hardware.builtin.redpitaya.proto.RxTrace;
import ax.xz.mri.hardware.builtin.redpitaya.proto.TxOutput;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.simulation.SignalTrace;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedPitayaDeviceFlowTest {

    private final RedPitayaPlugin plugin = new RedPitayaPlugin();

    @Test
    void runEmitsHelloBeginRunTxBlockGpioEventsEndRunInOrder() throws Exception {
        var cfg = ((RedPitayaConfig) plugin.defaultConfig())
            .withHostname("rp-test.local")
            .withRxGatePin(RedPitayaChannel.DIO0_P);

        var fake = new FakeTransport();
        // Server replies: HelloAck, Ack-for-BeginRun, RunResult
        fake.queueReply(Reply.newBuilder().setHelloAck(HelloAck.newBuilder()
            .setServerProtoMajor(RedPitayaPlugin.CLIENT_PROTO_MAJOR).setServerProtoMinor(0)
            .setGitSha("abcdef0").build()).build());
        fake.queueReply(Reply.newBuilder().setAck(Ack.newBuilder().setNote("ok").build()).build());

        var rx = RxTrace.newBuilder().setFirstSampleUs(100).setSamplePeriodUs(0.5);
        for (int i = 0; i < 16; i++) { rx.addI(0.1f * i); rx.addQ(-0.1f * i); }
        fake.queueReply(Reply.newBuilder().setRunResult(RunResult.newBuilder()
            .setRx(rx.build())
            .addMetrics(Metric.newBuilder().setKey("tx_samples_sent")
                .setValue(ParamValue.newBuilder().setI(64).build()).build())
            .build()).build());

        var device = new RedPitayaDevice(cfg, fake);
        var channels = plugin.capabilities().outputChannels();
        var steps = buildSteps(64, channels);

        var result = device.run(64e-9, channels, steps, null);

        // Verify command sequence
        assertEquals(Command.BodyCase.HELLO, fake.sent.get(0).getBodyCase());
        assertEquals(Command.BodyCase.BEGIN_RUN, fake.sent.get(1).getBodyCase());
        var begin = fake.sent.get(1).getBeginRun();
        assertEquals(64, begin.getTotalSamples());
        assertEquals(64e-9, begin.getDtSeconds(), 1e-15);
        assertEquals(TxOutput.TX_OUT1, begin.getSetup().getTxOutput());
        assertEquals(PinId.DIO0_P, begin.getSetup().getRxGatePin());
        assertEquals(channels.size(), begin.getChannelSlotIdsCount());

        // Single TxBlock since 64 < TX_BLOCK_SAMPLES
        long txBlocks = fake.sent.stream().filter(c -> c.getBodyCase() == Command.BodyCase.TX_BLOCK).count();
        assertEquals(1, txBlocks);
        var tx = fake.sent.stream().filter(c -> c.getBodyCase() == Command.BodyCase.TX_BLOCK).findFirst().orElseThrow().getTxBlock();
        assertEquals(64, tx.getICount());
        assertEquals(64, tx.getQCount());

        // GpioEvents: rising edge of DIO0_P at sample 16, falling edge at sample 32
        var gpios = fake.sent.stream().filter(c -> c.getBodyCase() == Command.BodyCase.GPIO_EVENT).toList();
        assertEquals(2, gpios.size(), "expect one rising + one falling edge for DIO0_P");
        assertEquals(16, gpios.get(0).getGpioEvent().getSampleIndex());
        assertTrue(gpios.get(0).getGpioEvent().getState());
        assertEquals(PinId.DIO0_P, gpios.get(0).getGpioEvent().getPin());
        assertEquals(32, gpios.get(1).getGpioEvent().getSampleIndex());
        assertEquals(false, gpios.get(1).getGpioEvent().getState());

        // EndRun is the final command
        assertEquals(Command.BodyCase.END_RUN, fake.sent.get(fake.sent.size() - 1).getBodyCase());

        // Result mapping
        assertEquals(16, result.probeTraces().primary().points().size());
        assertEquals(100.0, result.probeTraces().primary().points().get(0).tMicros(), 1e-9);
        assertEquals(100.5, result.probeTraces().primary().points().get(1).tMicros(), 1e-9);
        Map<String, String> meta = result.deviceMetadata();
        assertEquals("redpitaya.stockos", meta.get("device"));
        assertEquals("rp-test.local", meta.get("host"));
        assertEquals("64", meta.get("tx_samples_sent"));
    }

    @Test
    void multipleTxBlocksFlowWhenSampleCountExceedsBlockSize() throws Exception {
        var cfg = (RedPitayaConfig) plugin.defaultConfig();
        var fake = new FakeTransport();
        fake.queueReply(Reply.newBuilder().setHelloAck(HelloAck.newBuilder()
            .setServerProtoMajor(RedPitayaPlugin.CLIENT_PROTO_MAJOR).build()).build());
        fake.queueReply(Reply.newBuilder().setAck(Ack.getDefaultInstance()).build());
        fake.queueReply(Reply.newBuilder().setRunResult(RunResult.newBuilder()
            .setRx(RxTrace.newBuilder().setSamplePeriodUs(0.064).build()).build()).build());

        var channels = plugin.capabilities().outputChannels();
        int total = RedPitayaDevice.TX_BLOCK_SAMPLES * 2 + 17;   // 8209 samples
        var device = new RedPitayaDevice(cfg, fake);
        device.run(64e-9, channels, buildSteps(total, channels), null);

        long txBlocks = fake.sent.stream().filter(c -> c.getBodyCase() == Command.BodyCase.TX_BLOCK).count();
        assertEquals(3, txBlocks, "expect two full + one tail block");
        var blockSizes = fake.sent.stream()
            .filter(c -> c.getBodyCase() == Command.BodyCase.TX_BLOCK)
            .map(c -> c.getTxBlock().getICount())
            .toList();
        assertEquals(List.of(RedPitayaDevice.TX_BLOCK_SAMPLES, RedPitayaDevice.TX_BLOCK_SAMPLES, 17), blockSizes);
    }

    @Test
    void protocolVersionMismatchSurfacesAsHardwareException() {
        var cfg = (RedPitayaConfig) plugin.defaultConfig();
        var fake = new FakeTransport();
        fake.queueReply(Reply.newBuilder().setHelloAck(HelloAck.newBuilder()
            .setServerProtoMajor(RedPitayaPlugin.CLIENT_PROTO_MAJOR + 1).build()).build());

        var device = new RedPitayaDevice(cfg, fake);
        var ex = assertThrows(HardwareException.class, () ->
            device.run(64e-9, plugin.capabilities().outputChannels(), List.of(), null));
        assertTrue(ex.getMessage().contains("Protocol major mismatch"), ex.getMessage());
    }

    @Test
    void serverErrorOnBeginRunSurfacesAsHardwareException() {
        var cfg = (RedPitayaConfig) plugin.defaultConfig();
        var fake = new FakeTransport();
        fake.queueReply(Reply.newBuilder().setHelloAck(HelloAck.newBuilder()
            .setServerProtoMajor(RedPitayaPlugin.CLIENT_PROTO_MAJOR).build()).build());
        fake.queueReply(Reply.newBuilder().setError(ax.xz.mri.hardware.builtin.redpitaya.proto.Error.newBuilder()
            .setCode(ax.xz.mri.hardware.builtin.redpitaya.proto.ErrorCode.ERR_RX_TOO_BIG)
            .setMessage("DDR3 cap").build()).build());

        var device = new RedPitayaDevice(cfg, fake);
        var ex = assertThrows(HardwareException.class, () ->
            device.run(64e-9, plugin.capabilities().outputChannels(),
                buildSteps(8, plugin.capabilities().outputChannels()), null));
        assertTrue(ex.getMessage().contains("BeginRun rejected"));
        assertTrue(ex.getMessage().contains("DDR3 cap"));
    }

    @Test
    void dtBelowDecimationMinimumIsRejected() {
        var cfg = ((RedPitayaConfig) plugin.defaultConfig())
            .withSampleRate(RedPitayaSampleRate.DECIM_64);   // min dt ~512 ns
        var fake = new FakeTransport();
        var device = new RedPitayaDevice(cfg, fake);
        var ex = assertThrows(HardwareException.class, () ->
            device.run(100e-9, plugin.capabilities().outputChannels(), List.of(), null));
        assertTrue(ex.getMessage().contains("below the minimum"));
    }

    /**
     * Synthetic DDC: feed a clean cosine carrier into the device's RxTrace
     * with rxCarrierHz set to the same frequency. The host's DDC should
     * mix down to a settled DC magnitude ≈ the cosine amplitude (the
     * boxcar low-pass nulls the 2·carrier image perfectly when the window
     * is one carrier period).
     */
    @Test
    void ddcMatchedCarrierProducesSettledBaseband() throws Exception {
        double carrierHz = 1.0e6;
        double sampleRateHz = 125e6;
        double dtUs = 1e6 / sampleRateHz;
        int n = 4096;
        double amplitude = 0.7;

        var rx = RxTrace.newBuilder().setFirstSampleUs(0).setSamplePeriodUs(dtUs);
        for (int i = 0; i < n; i++) {
            double t = i / sampleRateHz;
            rx.addI((float) (amplitude * Math.cos(2 * Math.PI * carrierHz * t)));
            rx.addQ(0f);
        }

        var cfg = ((RedPitayaConfig) plugin.defaultConfig())
            .withRxCarrierHz(carrierHz)
            .withSampleRate(RedPitayaSampleRate.DECIM_1);   // matches the synthetic 125 MS/s ADC trace
        var fake = scriptedRunReplies(rx.build());
        var device = new RedPitayaDevice(cfg, fake);
        var result = device.run(8e-9, plugin.capabilities().outputChannels(),
            buildSteps(8, plugin.capabilities().outputChannels()), null);

        // I and Q components live on separate probes; each Point's imag
        // is zero. Combine them to get the true |I + jQ| magnitude (which
        // is rotation-invariant — the FPGA-pipeline phase correction
        // applied in buildTraces shouldn't move this).
        var iPts = result.probeTraces().byProbe().get(RedPitayaPlugin.PROBE_RX_I).points();
        var qPts = result.probeTraces().byProbe().get(RedPitayaPlugin.PROBE_RX_Q).points();
        assertEquals(n, iPts.size());
        assertEquals(n, qPts.size());

        // After the boxcar ramp-up, magnitude should settle to ≈ amplitude.
        int start = 200;
        double minMag = Double.POSITIVE_INFINITY, maxMag = 0, sumMag = 0;
        for (int i = start; i < n; i++) {
            double mag = Math.hypot(iPts.get(i).real(), qPts.get(i).real());
            if (mag < minMag) minMag = mag;
            if (mag > maxMag) maxMag = mag;
            sumMag += mag;
        }
        double meanMag = sumMag / (n - start);
        assertEquals(amplitude, meanMag, amplitude * 0.05,
            "DDC settled magnitude should match cosine amplitude");
        assertTrue((maxMag - minMag) < amplitude * 0.02,
            "DDC settled magnitude should be flat: range was [" + minMag + ", " + maxMag + "]");
    }

    /**
     * Synthetic DDC with mismatched TX and RX carriers: the demodulated
     * baseband should NOT be flat DC (some beat-note oscillation must
     * survive) and the magnitude should be in the right ballpark of the
     * input amplitude. We don't try to pin the exact beat frequency in this
     * unit test because the boxcar low-pass leaks at the sum-frequency
     * (which adds extra zero-crossings around the beat's quiet points);
     * verifying the exact spectrum is the live-RP test's job.
     */
    @Test
    void ddcOffsetCarrierProducesNonFlatBaseband() throws Exception {
        double txCarrier = 1.0e6;
        double rxCarrier = txCarrier - 100e3;          // 100 kHz IF
        double sampleRateHz = 125e6;
        double dtUs = 1e6 / sampleRateHz;
        int n = 8192;

        var rx = RxTrace.newBuilder().setFirstSampleUs(0).setSamplePeriodUs(dtUs);
        for (int i = 0; i < n; i++) {
            double t = i / sampleRateHz;
            rx.addI((float) Math.cos(2 * Math.PI * txCarrier * t));
            rx.addQ(0f);
        }

        var cfg = ((RedPitayaConfig) plugin.defaultConfig())
            .withRxCarrierHz(rxCarrier)
            .withSampleRate(RedPitayaSampleRate.DECIM_1);
        var fake = scriptedRunReplies(rx.build());
        var device = new RedPitayaDevice(cfg, fake);
        var result = device.run(8e-9, plugin.capabilities().outputChannels(),
            buildSteps(8, plugin.capabilities().outputChannels()), null);

        var pts = result.probeTraces().primary().points();
        int start = 1000;
        // Mismatched DDC: real part oscillates (NOT flat DC), magnitude is
        // bounded below the input amplitude (perfect identity match would
        // give magnitude == 1.0; a beat note rotates between |I| ≤ 1).
        int crossings = 0;
        double minRe = Double.POSITIVE_INFINITY, maxRe = Double.NEGATIVE_INFINITY;
        for (int i = start + 1; i < n; i++) {
            double re = pts.get(i).real();
            if (re < minRe) minRe = re;
            if (re > maxRe) maxRe = re;
            if ((pts.get(i - 1).real() >= 0) != (re >= 0)) crossings++;
        }
        assertTrue(crossings > 5,
            "mismatched DDC should produce an oscillating baseband, but only " + crossings + " zero crossings");
        assertTrue(maxRe - minRe > 0.5,
            "mismatched DDC's I component should swing through the unit interval; range=" + (maxRe - minRe));
    }

    /**
     * Synthetic DDC with carrier=0 must be the identity: raw ADC samples
     * pass through unchanged into the trace's real part, with Q forced to
     * zero. This is the "bypass DDC" mode that the live raw-RF tests rely
     * on.
     */
    @Test
    void ddcZeroCarrierIsIdentity() throws Exception {
        double sampleRateHz = 125e6;
        double dtUs = 1e6 / sampleRateHz;
        int n = 64;

        var rx = RxTrace.newBuilder().setFirstSampleUs(0).setSamplePeriodUs(dtUs);
        for (int i = 0; i < n; i++) {
            rx.addI((float) Math.sin(i * 0.123));      // arbitrary deterministic shape
            rx.addQ(0f);
        }
        var built = rx.build();

        var cfg = ((RedPitayaConfig) plugin.defaultConfig())
            .withRxCarrierHz(0)
            .withSampleRate(RedPitayaSampleRate.DECIM_1);
        var fake = scriptedRunReplies(built);
        var device = new RedPitayaDevice(cfg, fake);
        var result = device.run(8e-9, plugin.capabilities().outputChannels(),
            buildSteps(8, plugin.capabilities().outputChannels()), null);

        var pts = result.probeTraces().primary().points();
        assertEquals(n, pts.size());
        for (int i = 0; i < n; i++) {
            assertEquals(built.getI(i), pts.get(i).real(), 1e-7,
                "carrier=0 must pass real samples through unchanged at index " + i);
            assertEquals(0.0, pts.get(i).imag(), 1e-12,
                "carrier=0 forces Q=0 at index " + i);
        }
    }

    /** Build a fake transport pre-loaded with HelloAck, Ack, and a RunResult carrying the given trace. */
    private FakeTransport scriptedRunReplies(RxTrace trace) {
        var fake = new FakeTransport();
        fake.queueReply(Reply.newBuilder().setHelloAck(HelloAck.newBuilder()
            .setServerProtoMajor(RedPitayaPlugin.CLIENT_PROTO_MAJOR).build()).build());
        fake.queueReply(Reply.newBuilder().setAck(Ack.getDefaultInstance()).build());
        fake.queueReply(Reply.newBuilder().setRunResult(RunResult.newBuilder()
            .setRx(trace).build()).build());
        return fake;
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Build {@code n} PulseSteps. Drives TX_I with a constant 0.5, TX_Q with
     * 0, and DIO0_P high between sample 16 and 31 inclusive.
     */
    private static List<PulseStep> buildSteps(int n, List<SequenceChannel> channels) {
        int txI = channels.indexOf(RedPitayaChannel.TX_I.sequenceChannel());
        int txQ = channels.indexOf(RedPitayaChannel.TX_Q.sequenceChannel());
        int dio = channels.indexOf(RedPitayaChannel.DIO0_P.sequenceChannel());
        int width = channels.size();
        var steps = new ArrayList<PulseStep>(n);
        for (int k = 0; k < n; k++) {
            double[] controls = new double[width];
            controls[txI] = 0.5;
            controls[txQ] = 0.0;
            controls[dio] = (k >= 16 && k <= 31) ? 1.0 : 0.0;
            steps.add(new PulseStep(controls, controls[txI] != 0 ? 1 : 0));
        }
        return steps;
    }

    /** In-memory transport that records sent commands and replays scripted replies. */
    private static final class FakeTransport implements RedPitayaTransport {
        final List<Command> sent = new ArrayList<>();
        final Deque<Reply> replies = new ArrayDeque<>();

        void queueReply(Reply r) { replies.add(r); }

        @Override public void send(Command command) { sent.add(command); }

        @Override public Reply receive() throws IOException {
            var r = replies.poll();
            if (r == null) throw new IOException("no scripted reply available");
            return r;
        }

        @Override public void close() { /* nothing */ }
    }

    /**
     * Pins the residual-phase transfer function to its calibration
     * fixture: at every measured carrier the Java {@link
     * RedPitayaDevice#residualPhase} must reproduce the same arg(H) the
     * Python complex-residual fit produced (see commit message + the
     * coefficient block in {@code RedPitayaDevice}). Tolerance is set
     * to the fit's max residual (0.26°) plus a small numerical margin.
     *
     * <p>If this test fails, either the Java polynomial evaluator has
     * a bug or the constant block was edited without re-running the fit.
     */
    @Test
    void residualPhaseMatchesCalibrationFixture() {
        // Calibration data — must stay in sync with the coefficient
        // block in RedPitayaDevice. f in MHz, phi in degrees.
        double[] fMhz   = {0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 5.0,
                           6.5, 8.0, 10.0, 12.0, 14.0, 16.0, 18.0};
        double[] phiDeg = {0.03, 0.01, -0.08, -0.17, -0.33, -0.46, -0.54,
                           -0.62, -0.71, -1.05, -1.62, -2.79, -5.02, -11.88};

        // Tolerance: fit RMSE = 0.034°, max single-point residual = 0.26°.
        // Allow a hair more for accumulated float64 drift through the
        // 4th-order denominator.
        double maxAllowedDeg = 0.5;

        double maxObservedDeg = 0;
        for (int i = 0; i < fMhz.length; i++) {
            double f = fMhz[i] * 1e6;
            double phiPredDeg = Math.toDegrees(RedPitayaDevice.residualPhase(f));
            double err = Math.abs(phiPredDeg - phiDeg[i]);
            if (err > maxObservedDeg) maxObservedDeg = err;
            assertTrue(err < maxAllowedDeg,
                String.format("residualPhase(%.1f MHz) = %.3f°, expected %.3f°, off by %.3f°",
                    fMhz[i], phiPredDeg, phiDeg[i], err));
        }
    }

    /**
     * Sanity: residualPhase should evaluate to ≈ 0 at f = 0 (the model's
     * value is set by the b0/a0 ratio of the TF, which the fit found to
     * be very close to 0.80 — a real positive scalar — so arg(H) ≈ 0).
     * If a future re-fit moves this away from zero, the constant-baseband
     * trace at carrier=0 (where the DDC degenerates to identity) would
     * pick up a spurious rotation.
     */
    /**
     * Synthetic I/Q round-trip loopback. Build a known-shape envelope
     * (Gaussian) modulated by the configured carrier, feed it into the
     * DDC pipeline, and assert that the recovered baseband peaks at the
     * SAME time as the input envelope.
     *
     * <p>Reproduces the visible-shift bug the user reported in the
     * timeline: even after compensating for the boxcar group delay, the
     * I trace's peak landed downstream of the rp.rx envelope's peak. If
     * the alignment is correct, the I peak's sample index should equal
     * the input envelope peak's sample index to within a couple of dt.
     *
     * <p>Carrier and dt are chosen so the boxcar window is large enough
     * to make any group-delay miscompensation visible (carrier = 100 kHz,
     * dt = 1 µs ⇒ window = 10 samples ⇒ uncompensated shift would be
     * 4.5 µs ≈ 4.5 samples).
     */
    @Test
    void synthesizedLoopbackEnvelopePeakAlignsWithRxEnvelope() throws Exception {
        // Cover the carrier/dt regime where the boxcar window is large
        // (slow carriers / fast sampling): a window of 100 samples ⇒
        // group-delay correction of ~50 µs. If the compensation is even
        // slightly off, the alignment fails by tens of µs — exactly the
        // kind of shift the user reported.
        runRoundTripPeakAlignment(  100e3, 1e6, 1024);   // window =  10
        runRoundTripPeakAlignment(   10e3, 1e6, 4096);   // window = 100
        runRoundTripPeakAlignment(    1e6, 125e6, 16384); // window = 125, decim_1
        runRoundTripPeakAlignment(   50e3, 5e5, 2048);   // window =  10, slow sample
    }

    private void runRoundTripPeakAlignment(double carrierHz, double sampleRateHz, int n) throws Exception {
        double dtUs = 1e6 / sampleRateHz;

        // Gaussian envelope centered at sample n/2.
        int peakIdx = n / 2;
        double sigmaSamples = 50.0;
        double[] envelope = new double[n];
        for (int i = 0; i < n; i++) {
            double d = (i - peakIdx) / sigmaSamples;
            envelope[i] = Math.exp(-0.5 * d * d);
        }

        // Synthetic ADC: envelope · cos(2π·f·t). No FPGA delay or filter
        // distortion in this test — we want to isolate the DDC's own
        // alignment behavior, not the analog-frontend correction.
        var rx = RxTrace.newBuilder().setFirstSampleUs(0).setSamplePeriodUs(dtUs);
        for (int i = 0; i < n; i++) {
            double t = i / sampleRateHz;
            rx.addI((float) (envelope[i] * Math.cos(2 * Math.PI * carrierHz * t)));
            rx.addQ(0f);
        }

        var cfg = ((RedPitayaConfig) plugin.defaultConfig())
            .withTxCarrierHz(carrierHz)
            .withRxCarrierHz(carrierHz)
            .withSampleRate(RedPitayaSampleRate.DECIM_1);
        var fake = scriptedRunReplies(rx.build());
        var device = new RedPitayaDevice(cfg, fake);
        var result = device.run(8e-9, plugin.capabilities().outputChannels(),
            buildSteps(8, plugin.capabilities().outputChannels()), null);

        var rxPts = result.probeTraces().byProbe().get(RedPitayaPlugin.PROBE_RX).points();
        var iPts  = result.probeTraces().byProbe().get(RedPitayaPlugin.PROBE_RX_I).points();
        var qPts  = result.probeTraces().byProbe().get(RedPitayaPlugin.PROBE_RX_Q).points();

        // Envelope peak of rp.rx (carrier-modulated): smooth |raw|² over
        // one carrier period — same physical envelope the user sees.
        double rxPeakUs = envelopePeakUs(rxPts, dtUs, carrierHz, sampleRateHz);

        // Peak of rp.rx.i — already smoothed by the DDC's boxcar, so we
        // just look for max(|I + jQ|).
        double iPeakUs = magnitudePeakUs(iPts, qPts);

        double envelopePeakUs = peakIdx * dtUs;
        System.out.printf("Envelope peak = %.3f µs%n", envelopePeakUs);
        System.out.printf("rp.rx peak    = %.3f µs%n", rxPeakUs);
        System.out.printf("rp.rx.i peak  = %.3f µs (shift vs rx = %+.3f µs)%n",
            iPeakUs, iPeakUs - rxPeakUs);

        assertTrue(Math.abs(iPeakUs - rxPeakUs) < 2 * dtUs,
            "rp.rx.i envelope peak shifted from rp.rx envelope peak by "
                + (iPeakUs - rxPeakUs) + " µs (more than 2·dt)");
    }

    /**
     * Time of the envelope peak of a carrier-modulated trace: smooth
     * {@code real²} over one carrier period (boxcar), then locate the
     * max and centroid-shift back by half the window so the result is
     * the time of the underlying envelope's peak — not the smoothing
     * filter's own group delay.
     */
    private static double envelopePeakUs(List<SignalTrace.Point> pts,
                                         double dtUs, double carrierHz, double fs) {
        int n = pts.size();
        int window = Math.max(1, (int) Math.round(fs / carrierHz));
        double sum = 0;
        int maxIdx = 0;
        double maxVal = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            double v = pts.get(i).real();
            sum += v * v;
            if (i >= window) {
                double drop = pts.get(i - window).real();
                sum -= drop * drop;
            }
            if (sum > maxVal) { maxVal = sum; maxIdx = i; }
        }
        return pts.get(maxIdx).tMicros() - (window - 1) * 0.5 * dtUs;
    }

    /** Time of {@code max(|I + jQ|)} across two real-valued traces. */
    private static double magnitudePeakUs(List<SignalTrace.Point> iPts,
                                          List<SignalTrace.Point> qPts) {
        int maxIdx = 0;
        double maxMag = -1;
        for (int i = 0; i < iPts.size(); i++) {
            double mag = Math.hypot(iPts.get(i).real(), qPts.get(i).real());
            if (mag > maxMag) { maxMag = mag; maxIdx = i; }
        }
        return iPts.get(maxIdx).tMicros();
    }

    @Test
    void residualPhaseAtDcIsApproximatelyZero() {
        double phi0 = RedPitayaDevice.residualPhase(0.0);
        assertTrue(Math.abs(Math.toDegrees(phi0)) < 0.5,
            "residualPhase(0) = " + Math.toDegrees(phi0) + "° — expected near 0");
    }
}
