package ax.xz.mri.hardware.builtin.redpitaya;

import ax.xz.mri.hardware.HardwareDevice;
import ax.xz.mri.hardware.HardwareException;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Ack;
import ax.xz.mri.hardware.builtin.redpitaya.proto.BeginRun;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Command;
import ax.xz.mri.hardware.builtin.redpitaya.proto.EndRun;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Error;
import ax.xz.mri.hardware.builtin.redpitaya.proto.GpioEvent;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Hello;
import ax.xz.mri.hardware.builtin.redpitaya.proto.HelloAck;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Metric;
import ax.xz.mri.hardware.builtin.redpitaya.proto.PinId;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Reply;
import ax.xz.mri.hardware.builtin.redpitaya.proto.RunResult;
import ax.xz.mri.hardware.builtin.redpitaya.proto.RunSetup;
import ax.xz.mri.hardware.builtin.redpitaya.proto.RxTrace;
import ax.xz.mri.hardware.builtin.redpitaya.proto.TxBlock;
import ax.xz.mri.model.scenario.RunResult.Hardware;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.simulation.MultiProbeSignalTrace;
import ax.xz.mri.model.simulation.SignalTrace;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;

/**
 * Live handle to one {@link RedPitayaPlugin} run. Constructed by the plugin
 * with an open {@link RedPitayaTransport}; the device owns it and closes it
 * in {@link #close()}.
 *
 * <p>The protocol the device speaks:
 * <ol>
 *   <li>{@code Hello -> HelloAck} (version handshake)</li>
 *   <li>{@code BeginRun(RunSetup, total, dt) -> Ack | Error}</li>
 *   <li>{@code TxBlock} ×N (no reply per block)</li>
 *   <li>{@code GpioEvent} ×M (no reply per event)</li>
 *   <li>{@code EndRun -> RunResult | Error}</li>
 * </ol>
 *
 * <p>The server queues TX samples and GPIO events as they arrive, then
 * starts DAC playback on EndRun. Send order between TxBlock and GpioEvent
 * does not matter — events are dispatched by sample index.
 */
public final class RedPitayaDevice implements HardwareDevice {

    static final int TX_BLOCK_SAMPLES = 4096;
    private static final double GPIO_THRESHOLD = 0.5;
    private static final double TX_PROGRESS_FRACTION = 0.5;

    private final RedPitayaConfig config;
    private final RedPitayaTransport transport;
    private boolean closed;
    /** Lazy: handshake (Hello/HelloAck) runs once, then is cached for the device's lifetime. */
    private boolean handshakeDone;

    public RedPitayaDevice(RedPitayaConfig config, RedPitayaTransport transport) {
        this.config = config;
        this.transport = transport;
    }

    @Override
    public Hardware run(double dtSeconds,
                        List<SequenceChannel> channelSlots,
                        List<PulseStep> steps,
                        DoubleConsumer progress) throws HardwareException {
        if (closed) throw new HardwareException("RedPitayaDevice already closed");

        validateDt(dtSeconds);

        try {
            handshake();

            int totalSteps = steps.size();
            sendBeginRun(channelSlots, totalSteps, dtSeconds);

            int[] slotForChannel = resolveSlots(channelSlots);
            streamPulse(steps, slotForChannel, progress);

            transport.send(Command.newBuilder().setEndRun(EndRun.getDefaultInstance()).build());
            Reply reply = transport.receive();
            if (progress != null) progress.accept(1.0);

            return buildResult(reply);
        } catch (IOException ex) {
            throw new HardwareException(
                "Communication with " + config.hostname() + ":" + config.port() + " failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        transport.close();
    }

    // --- Handshake ----------------------------------------------------------

    private void handshake() throws IOException, HardwareException {
        if (handshakeDone) return;
        transport.send(Command.newBuilder().setHello(Hello.newBuilder()
            .setClientProtoMajor(RedPitayaPlugin.CLIENT_PROTO_MAJOR)
            .setClientProtoMinor(RedPitayaPlugin.CLIENT_PROTO_MINOR)
            .build()).build());
        Reply reply = transport.receive();
        if (reply.getBodyCase() != Reply.BodyCase.HELLO_ACK) {
            throw new HardwareException("Expected HelloAck, got " + reply.getBodyCase()
                + (reply.hasError() ? ": " + reply.getError().getMessage() : ""));
        }
        HelloAck ack = reply.getHelloAck();
        if (ack.getServerProtoMajor() != RedPitayaPlugin.CLIENT_PROTO_MAJOR) {
            throw new HardwareException(
                "Protocol major mismatch: client=" + RedPitayaPlugin.CLIENT_PROTO_MAJOR
                    + ", server=" + ack.getServerProtoMajor() + ". Update mri-rp-server.");
        }
        handshakeDone = true;
    }

    private void sendBeginRun(List<SequenceChannel> channelSlots, int totalSteps, double dtSeconds)
            throws IOException, HardwareException {
        var setup = RunSetup.newBuilder()
            .setTxCarrierHz(config.txCarrierHz())
            .setRxCarrierHz(config.rxCarrierHz())
            .setDecimation(config.sampleRate().decimation())
            .setTxOutput(config.txPort().proto())
            .setTxGain((float) config.txGain())
            .setRxGatePin(config.rxGatePin() == null ? PinId.PIN_UNSPECIFIED : config.rxGatePin().pinId())
            .build();
        var begin = BeginRun.newBuilder()
            .setSetup(setup)
            .setTotalSamples(totalSteps)
            .setDtSeconds(dtSeconds);
        for (var ch : channelSlots) begin.addChannelSlotIds(ch.sourceName() + "[" + ch.subIndex() + "]");

        transport.send(Command.newBuilder().setBeginRun(begin.build()).build());
        Reply reply = transport.receive();
        if (reply.getBodyCase() == Reply.BodyCase.ERROR) {
            Error err = reply.getError();
            throw new HardwareException("BeginRun rejected (" + err.getCode() + "): " + err.getMessage());
        }
        if (reply.getBodyCase() != Reply.BodyCase.ACK) {
            throw new HardwareException("Expected Ack for BeginRun, got " + reply.getBodyCase());
        }
    }

    private void validateDt(double dtSeconds) throws HardwareException {
        double minDt = config.sampleRate().minDtSeconds();
        if (dtSeconds < minDt) {
            throw new HardwareException(String.format(
                "Sequence dt %.3f ns is below the minimum %.3f ns at decimation %d. "
                    + "Lower decimation or coarsen sequence dt.",
                dtSeconds * 1e9, minDt * 1e9, config.sampleRate().decimation()));
        }
    }

    // --- Per-step streaming -------------------------------------------------

    /** Index of each {@link RedPitayaChannel} in the run's {@code channelSlots}, or -1 if absent. */
    private int[] resolveSlots(List<SequenceChannel> channelSlots) {
        var values = RedPitayaChannel.values();
        int[] indices = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            indices[i] = -1;
            var target = values[i].sequenceChannel();
            for (int j = 0; j < channelSlots.size(); j++) {
                if (channelSlots.get(j).equals(target)) { indices[i] = j; break; }
            }
        }
        return indices;
    }

    private void streamPulse(List<PulseStep> steps, int[] slotForChannel, DoubleConsumer progress)
            throws IOException {
        int total = steps.size();
        int txISlot = slotForChannel[RedPitayaChannel.TX_I.ordinal()];
        int txQSlot = slotForChannel[RedPitayaChannel.TX_Q.ordinal()];

        boolean[] gpioState = new boolean[RedPitayaChannel.values().length];

        var txI = new float[TX_BLOCK_SAMPLES];
        var txQ = new float[TX_BLOCK_SAMPLES];
        int blockFill = 0;

        for (int k = 0; k < total; k++) {
            PulseStep step = steps.get(k);

            txI[blockFill] = txISlot >= 0 ? (float) step.control(txISlot) : 0f;
            txQ[blockFill] = txQSlot >= 0 ? (float) step.control(txQSlot) : 0f;
            blockFill++;
            if (blockFill == TX_BLOCK_SAMPLES) {
                sendTxBlock(txI, txQ, blockFill);
                blockFill = 0;
            }

            for (RedPitayaChannel ch : RedPitayaChannel.gpioPins()) {
                int slot = slotForChannel[ch.ordinal()];
                if (slot < 0) continue;
                boolean wantHigh = step.control(slot) >= GPIO_THRESHOLD;
                if (wantHigh != gpioState[ch.ordinal()]) {
                    gpioState[ch.ordinal()] = wantHigh;
                    transport.send(Command.newBuilder().setGpioEvent(GpioEvent.newBuilder()
                        .setSampleIndex(k)
                        .setPin(ch.pinId())
                        .setState(wantHigh)
                        .build()).build());
                }
            }

            if (progress != null && (k & 0xFF) == 0 && total > 0) {
                progress.accept(TX_PROGRESS_FRACTION * k / total);
            }
        }
        if (blockFill > 0) sendTxBlock(txI, txQ, blockFill);
        if (progress != null) progress.accept(TX_PROGRESS_FRACTION);
    }

    private void sendTxBlock(float[] iBuf, float[] qBuf, int len) throws IOException {
        var block = TxBlock.newBuilder();
        for (int n = 0; n < len; n++) {
            block.addI(iBuf[n]);
            block.addQ(qBuf[n]);
        }
        transport.send(Command.newBuilder().setTxBlock(block.build()).build());
    }

    // --- Result -------------------------------------------------------------

    private Hardware buildResult(Reply reply) throws HardwareException {
        if (reply.getBodyCase() == Reply.BodyCase.ERROR) {
            Error err = reply.getError();
            throw new HardwareException("Run failed (" + err.getCode() + "): " + err.getMessage());
        }
        if (reply.getBodyCase() != Reply.BodyCase.RUN_RESULT) {
            throw new HardwareException("Expected RunResult after EndRun, got " + reply.getBodyCase());
        }
        RunResult result = reply.getRunResult();
        var traces = buildTraces(result.getRx(), config.rxCarrierHz());

        var metadata = new LinkedHashMap<String, String>();
        metadata.put("device", "redpitaya.stockos");
        metadata.put("host", config.hostname());
        metadata.put("tx_carrier_hz", Double.toString(config.txCarrierHz()));
        metadata.put("rx_carrier_hz", Double.toString(config.rxCarrierHz()));
        metadata.put("decimation", Integer.toString(config.sampleRate().decimation()));
        metadata.put("tx_output", config.txPort().name());
        for (Metric m : result.getMetricsList()) metadata.put(m.getKey(), formatParamValue(m));
        return new Hardware(List.of(), traces, metadata);
    }

    /**
     * Linear pipeline delay through the FPGA signal chain (AWG BRAM read,
     * DAC reconstruction filter group delay, ADC input pipeline, dfilt1
     * equalizer). Pinned at 305 ns by single-frequency calibration at
     * 1 MHz and confirmed (residual &lt; 0.5°) at every measured carrier
     * up to 5 MHz. Above ~10 MHz, additional non-linear-phase distortion
     * from the analog AAF / reconstruction filter pair becomes
     * significant; that part is captured by the residual transfer
     * function {@link #RP_RESIDUAL_TF_NUM} / {@link #RP_RESIDUAL_TF_DEN}.
     */
    private static final double RP_FPGA_PIPELINE_DELAY_S = 305e-9;

    /**
     * Frequency normalization for the residual transfer function — keeps
     * the polynomial coefficients O(1) so float64 arithmetic stays well-
     * conditioned. Equal to {@code 2π · 20 MHz}.
     */
    private static final double RP_RESIDUAL_TF_S_SCALE = 2.0 * Math.PI * 20.0e6;

    /**
     * Numerator coefficients of the residual-phase transfer function
     * {@code H(s_norm) = N(s_norm) / D(s_norm)} where
     * {@code s_norm = s / RP_RESIDUAL_TF_S_SCALE} and {@code s = j·2π·f}.
     * Coefficients are stored low-power-first: index {@code i} multiplies
     * {@code s_norm^i}.
     *
     * <p>Fit to 14-point loopback sweep (0.5 → 18 MHz) of {@code |H|}
     * and {@code arg(H)} on a STEMlab 125-14, joint complex-residual
     * least-squares with M=2, N=4. Magnitude R² = 0.999, phase R² =
     * 0.998, max phase residual 0.26° across the calibration range.
     *
     * <p>The pole structure picks up a real analog resonance near
     * 21 MHz (Q ≈ 2) plus a non-minimum-phase pair near 19 MHz that
     * captures the {@code red_pitaya_dfilt1.v} ADC equalizer's
     * frequency-dependent group delay — both expected from the
     * stock bitstream + AD9645/AD9767 analog frontend.
     *
     * <p>Stored to {@code _NUM} / {@code _DEN} rather than as
     * pole-zero pairs so the runtime evaluation is one polynomial
     * pass per call, no complex root-finding.
     *
     * <p>The TF describes the residual <em>after</em> the 305 ns linear
     * delay correction has been applied. Total rotation in the DDC is
     * therefore {@code 2π·f·τ - arg(H(j·2π·f))} (subtract because the
     * captured baseband carries {@code +arg(H)} and we want to undo it).
     */
    private static final double[] RP_RESIDUAL_TF_NUM = {
        +9.9345950034e-01,    // b0
        -3.2435950189e-01,    // b1
        +6.1534261075e-01,    // b2
    };

    /**
     * Denominator coefficients of the residual-phase transfer function.
     * Same indexing as {@link #RP_RESIDUAL_TF_NUM}; the constant a_N is
     * fixed at 1.0 to remove the gain-ambiguity, included explicitly
     * here for symmetry.
     */
    private static final double[] RP_RESIDUAL_TF_DEN = {
        +1.2388997134e+00,    // a0
        -3.5224937026e-01,    // a1
        +1.8271959840e+00,    // a2
        -2.4247834104e-01,    // a3
        +1.0000000000e+00,    // a4
    };

    /**
     * Phase of the residual transfer function {@code arg(H(j·2π·f))} at
     * the given carrier, in radians. Computed from
     * {@code arg(N/D) = arg(N · conj(D))} so we only call {@link
     * Math#atan2} once instead of twice (and avoid an unwrap step).
     */
    static double residualPhase(double carrierHz) {
        double w = 2.0 * Math.PI * carrierHz / RP_RESIDUAL_TF_S_SCALE;
        double[] num = evalPolyAtJW(RP_RESIDUAL_TF_NUM, w);
        double[] den = evalPolyAtJW(RP_RESIDUAL_TF_DEN, w);
        double numRe = num[0], numIm = num[1];
        double denRe = den[0], denIm = den[1];
        return Math.atan2(numIm * denRe - numRe * denIm,
                          numRe * denRe + numIm * denIm);
    }

    /**
     * Evaluate a polynomial with real coefficients at {@code s = j·w}.
     * Returns {@code [real, imag]}. Powers of {@code j} alternate
     * {@code 1, j, -1, -j, 1, ...}, so the result splits into two real
     * sums in {@code w}.
     */
    private static double[] evalPolyAtJW(double[] coefs, double w) {
        double re = 0, im = 0;
        double power = 1.0;
        for (int i = 0; i < coefs.length; i++) {
            double term = coefs[i] * power;
            switch (i & 3) {
                case 0 -> re += term;          //  j^0 = +1
                case 1 -> im += term;          //  j^1 = +j
                case 2 -> re -= term;          //  j^2 = -1
                case 3 -> im -= term;          //  j^3 = -j
            }
            power *= w;
        }
        return new double[]{re, im};
    }

    /**
     * Build the three RX probes the plugin advertises:
     * <ul>
     *   <li>{@link RedPitayaPlugin#PROBE_RX} — raw ADC samples from the
     *       device, real-valued (the carrier is still in place).</li>
     *   <li>{@link RedPitayaPlugin#PROBE_RX_I} — in-phase component of the
     *       digitally down-converted signal, real-valued. For
     *       {@code carrierHz == 0} this equals the raw ADC (DDC degenerates
     *       to identity).</li>
     *   <li>{@link RedPitayaPlugin#PROBE_RX_Q} — quadrature component of
     *       the DDC, real-valued. Always zero for {@code carrierHz == 0}.</li>
     * </ul>
     *
     * <p>The C server ships raw ADC in {@code rx.i[]} with {@code rx.q[]}
     * zero-filled (see {@code rx.h} — "v1 returns raw ADC, host can DDC").
     * The DDC math, when a non-zero carrier is configured:
     *
     * <pre>
     *   I_bb[n] = 2 · adc[n] · cos(2π · f · n · dt)
     *   Q_bb[n] = -2 · adc[n] · sin(2π · f · n · dt)
     * </pre>
     *
     * <p>followed by a boxcar low-pass sized to one carrier period — exact
     * null at the carrier and at 2·carrier (the mixer's sum-frequency
     * image), unity gain at DC. Cheaper than a biquad and good enough for
     * visualisation; if MR-grade SNR matters we can swap in a windowed
     * sinc later.
     *
     * <p>The primary probe is {@link RedPitayaPlugin#PROBE_RX_I} — for
     * carrier=0 it shows the actual baseband (since DDC is identity); for
     * non-DC it shows the demodulated in-phase component, which is the
     * most natural single-trace summary of a carrier-modulated signal.
     */
    private static MultiProbeSignalTrace buildTraces(RxTrace rx, double carrierHz) {
        int n = rx.getICount();
        double t0 = rx.getFirstSampleUs();
        double dtUs = rx.getSamplePeriodUs();
        double dtSec = dtUs * 1e-6;

        var rawPts = new java.util.ArrayList<SignalTrace.Point>(n);
        var iPts   = new java.util.ArrayList<SignalTrace.Point>(n);
        var qPts   = new java.util.ArrayList<SignalTrace.Point>(n);

        if (carrierHz == 0.0 || dtSec <= 0 || n == 0) {
            // DDC degenerates to identity: I = raw ADC, Q = 0.
            for (int i = 0; i < n; i++) {
                double t = t0 + i * dtUs;
                double a = rx.getI(i);
                rawPts.add(new SignalTrace.Point(t, a, 0.0));
                iPts.add(new SignalTrace.Point(t, a, 0.0));
                qPts.add(new SignalTrace.Point(t, 0.0, 0.0));
            }
        } else {
            // Single-pass DDC: mix down + sliding-boxcar low-pass + emit
            // all three traces in lockstep.
            //
            // Hardware phase correction folded into the mixer's initial
            // phase. The captured baseband carries {@code +arg(H)} from
            // the FPGA + analog chain (305 ns linear delay + the
            // residual-phase TF, see {@link #residualPhase}); rotation
            // commutes with the linear boxcar so we can apply it
            // up-front by starting the LO phase at {@code -correction}
            // instead of running a separate post-rotation pass.
            //
            // Boxcar low-pass: window = one carrier period, exact null at
            // the carrier and 2·carrier image. Group delay
            // {@code (window-1)/2} samples is compensated by shifting the
            // I/Q timestamp axis back by the same amount, so the
            // demodulated envelope time-aligns with {@code rp.rx}.
            double phaseStep = 2.0 * Math.PI * carrierHz * dtSec;
            double phase = -(2.0 * Math.PI * carrierHz * RP_FPGA_PIPELINE_DELAY_S
                          - residualPhase(carrierHz));
            int window = boxcarWindow(carrierHz, dtSec, n);
            double tShiftUs = (window - 1) * 0.5 * dtUs;

            double[] iMix = new double[n];
            double[] qMix = new double[n];
            double sumI = 0, sumQ = 0;
            for (int i = 0; i < n; i++) {
                double a = rx.getI(i);
                iMix[i] =  a * 2.0 * Math.cos(phase);
                qMix[i] = -a * 2.0 * Math.sin(phase);
                phase += phaseStep;
                if (phase >  Math.PI) phase -= 2.0 * Math.PI;

                sumI += iMix[i];
                sumQ += qMix[i];
                if (i >= window) {
                    sumI -= iMix[i - window];
                    sumQ -= qMix[i - window];
                }

                double t = t0 + i * dtUs;
                rawPts.add(new SignalTrace.Point(t, a, 0.0));
                iPts.add(new SignalTrace.Point(t - tShiftUs, sumI / window, 0.0));
                qPts.add(new SignalTrace.Point(t - tShiftUs, sumQ / window, 0.0));
            }
        }

        var byProbe = new LinkedHashMap<String, SignalTrace>();
        byProbe.put(RedPitayaPlugin.PROBE_RX,   new SignalTrace(rawPts));
        byProbe.put(RedPitayaPlugin.PROBE_RX_I, new SignalTrace(iPts));
        byProbe.put(RedPitayaPlugin.PROBE_RX_Q, new SignalTrace(qPts));
        return new MultiProbeSignalTrace(byProbe, RedPitayaPlugin.PROBE_RX_I);
    }

    /**
     * Length of the DDC's boxcar low-pass FIR — one carrier period in
     * samples, clamped to {@code [1, n/4]}. Exact null at the carrier
     * and at 2·carrier (the mixer's sum-frequency image), unity gain at
     * DC. Group delay = {@code (window-1)/2} samples.
     */
    private static int boxcarWindow(double carrierHz, double dtSec, int n) {
        return (int) Math.max(1, Math.min(n / 4,
            Math.round(1.0 / Math.abs(carrierHz * dtSec))));
    }

    private static String formatParamValue(Metric m) {
        var v = m.getValue();
        return switch (v.getVCase()) {
            case B -> Boolean.toString(v.getB());
            case I -> Long.toString(v.getI());
            case D -> Double.toString(v.getD());
            case S -> v.getS();
            case RAW -> "<" + v.getRaw().size() + " bytes>";
            case V_NOT_SET -> "";
        };
    }
}
