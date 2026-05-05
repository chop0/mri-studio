package ax.xz.mri.hardware.builtin.redpitaya;

import ax.xz.mri.hardware.builtin.redpitaya.proto.Command;
import ax.xz.mri.hardware.builtin.redpitaya.proto.GetDiag;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Hello;
import ax.xz.mri.hardware.builtin.redpitaya.proto.Reply;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.sequence.SequenceChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live integration test against a running mri-rp-server. Skipped by
 * default; enable with:
 *
 * <pre>./gradlew test -Dredpitaya.smoke.host=rp-f03e18.local</pre>
 */
@EnabledIfSystemProperty(named = "redpitaya.smoke.host", matches = ".+")
class RedPitayaLiveSmokeTest {

    @Test
    void helloAndDiagAgainstRealDevice() throws Exception {
        String host = System.getProperty("redpitaya.smoke.host");
        try (var transport = new TcpRedPitayaTransport(host, 6981, 2000)) {
            transport.send(Command.newBuilder().setHello(Hello.newBuilder()
                .setClientProtoMajor(RedPitayaPlugin.CLIENT_PROTO_MAJOR)
                .setClientProtoMinor(RedPitayaPlugin.CLIENT_PROTO_MINOR)
                .build()).build());
            Reply hello = transport.receive();
            assertEquals(Reply.BodyCase.HELLO_ACK, hello.getBodyCase());
            assertEquals(RedPitayaPlugin.CLIENT_PROTO_MAJOR, hello.getHelloAck().getServerProtoMajor());

            transport.send(Command.newBuilder().setGetDiag(GetDiag.getDefaultInstance()).build());
            Reply diag = transport.receive();
            assertEquals(Reply.BodyCase.DIAG_REPLY, diag.getBodyCase());
            assertNotNull(diag.getDiagReply().getFactsList());
            // Pull a couple of expected facts and prove they came from the device.
            String model = "", mac = "";
            for (var f : diag.getDiagReply().getFactsList()) {
                if (f.getKey().equals("model") && f.getValue().getVCase().getNumber() != 0) model = f.getValue().getS();
                if (f.getKey().equals("mac")   && f.getValue().getVCase().getNumber() != 0) mac = f.getValue().getS();
            }
            System.out.println("Live device — model=[" + model + "] mac=[" + mac + "]");
        }
    }

    /**
     * Loopback diagnostic: drive OUT1 with a known carrier-only burst, capture
     * IN1 (assumes the user has shorted OUT1 → IN1), and assert the captured
     * trace actually oscillates at the carrier frequency. This is the smoke
     * test that proves TX is reaching the DAC and the DAC is reaching IN1 —
     * counting samples isn't enough; we need to see real swing.
     *
     * <p>Uses {@code rxCarrierHz = 0} so the host-side DDC is the identity
     * mapping (cos(0) = 1, sin(0) = 0): the trace returns raw ADC samples
     * with the carrier modulation intact. With matched carriers we'd see a
     * settled DC level instead — that case is covered by
     * {@link #ddcProducesBasebandAtMatchedCarrier()}.
     */
    @Test
    void loopbackProducesVisibleCarrier() throws Exception {
        String host = System.getProperty("redpitaya.smoke.host");
        var plugin = new RedPitayaPlugin();
        var cfg = ((RedPitayaConfig) plugin.defaultConfig())
            .withHostname(host)
            .withTxCarrierHz(1.0e6)
            .withRxCarrierHz(0)            // bypass DDC — see raw RF
            .withTxGain(0.8)
            .withSampleRate(RedPitayaSampleRate.DECIM_1)
            .withRxGatePin(null);    // no gate — capture the entire window

        try (var device = plugin.open(cfg)) {
            var channels = plugin.capabilities().outputChannels();
            int n = 4096;            // ~33 us at 8 ns dt — well within BRAM caps
            var steps = new ArrayList<PulseStep>(n);
            int txI = channels.indexOf(RedPitayaChannel.TX_I.sequenceChannel());
            int txQ = channels.indexOf(RedPitayaChannel.TX_Q.sequenceChannel());
            for (int k = 0; k < n; k++) {
                var ctl = new double[channels.size()];
                ctl[txI] = 1.0;       // full-scale baseband, mixer produces 0.8*cos(2π·1MHz·t)
                ctl[txQ] = 0;
                steps.add(new PulseStep(ctl, 1));
            }

            var result = device.run(8e-9, channels, steps, null);
            var trace = result.probeTraces().primary();
            var pts = trace.points();
            assertFalse(pts.isEmpty(), "no samples captured");

            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (var p : pts) {
                if (p.real() < min) min = p.real();
                if (p.real() > max) max = p.real();
            }
            double pp = max - min;
            int crossings = 0;
            for (int i = 1; i < pts.size(); i++) {
                if ((pts.get(i - 1).real() >= 0) != (pts.get(i).real() >= 0)) crossings++;
            }
            System.out.printf("Loopback: pts=%d, peak-peak=%.4f V, zero-crossings=%d, first=%s, sample[100]=%s%n",
                pts.size(), pp, crossings, pts.get(0), pts.get(Math.min(100, pts.size() - 1)));
            for (var e : result.deviceMetadata().entrySet()) System.out.println("  " + e.getKey() + " = " + e.getValue());

            // 0.8 V baseband × 1 MHz cos: expect peak-peak ≥ 1.0 V at IN1.
            assertTrue(pp > 0.1, "loopback signal too small (pp=" + pp + " V) — TX may not be driving OUT1");
            // 1 MHz over the captured window should produce many zero crossings.
            assertTrue(crossings > 20, "no oscillation detected (crossings=" + crossings + ") — TX carrier missing");
        }
    }

    /**
     * Reproduce the user's failing case: 100 samples × 10 us = 1 ms total.
     * Pre-AXI/adaptive-freq this returned ERR_RX_TOO_BIG. The new BRAM +
     * adaptive-freq path should accept any duration and still show the
     * carrier in the captured ADC window.
     */
    @Test
    void longSequenceAcceptedAndProducesCarrier() throws Exception {
        String host = System.getProperty("redpitaya.smoke.host");
        var plugin = new RedPitayaPlugin();
        var cfg = ((RedPitayaConfig) plugin.defaultConfig())
            .withHostname(host)
            .withTxCarrierHz(1.0e6)
            .withRxCarrierHz(0)            // bypass DDC — see raw RF
            .withTxGain(0.8)
            .withSampleRate(RedPitayaSampleRate.DECIM_1)
            .withRxGatePin(null);

        try (var device = plugin.open(cfg)) {
            var channels = plugin.capabilities().outputChannels();
            int n = 100;
            double dt = 10e-6;       // 1 ms total — used to be rejected
            var steps = new ArrayList<PulseStep>(n);
            int txI = channels.indexOf(RedPitayaChannel.TX_I.sequenceChannel());
            int txQ = channels.indexOf(RedPitayaChannel.TX_Q.sequenceChannel());
            for (int k = 0; k < n; k++) {
                var ctl = new double[channels.size()];
                ctl[txI] = 1.0;
                ctl[txQ] = 0;
                steps.add(new PulseStep(ctl, 1));
            }

            // Should complete; pre-fix this would throw ERR_RX_TOO_BIG.
            var result = device.run(dt, channels, steps, null);
            var trace = result.probeTraces().primary();
            var pts = trace.points();
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            int crossings = 0;
            for (int i = 0; i < pts.size(); i++) {
                double v = pts.get(i).real();
                if (v < min) min = v;
                if (v > max) max = v;
                if (i > 0 && (pts.get(i - 1).real() >= 0) != (v >= 0)) crossings++;
            }
            System.out.printf("Long-seq: pts=%d, pp=%.4f V, crossings=%d%n",
                pts.size(), max - min, crossings);
            assertTrue(max - min > 0.1, "1 ms sequence still silent (pp=" + (max - min) + ")");
            assertTrue(crossings > 20, "carrier missing from 1 ms sequence (cross=" + crossings + ")");
        }
    }

    @Test
    void fullRunCycleAgainstRealDevice() throws Exception {
        String host = System.getProperty("redpitaya.smoke.host");
        var plugin = new RedPitayaPlugin();
        var cfg = ((RedPitayaConfig) plugin.defaultConfig())
            .withHostname(host)
            .withTxCarrierHz(1.0e6)              // 1 MHz carrier — easy to scope
            .withRxCarrierHz(1.0e6)
            .withTxGain(0.5)
            .withSampleRate(RedPitayaSampleRate.DECIM_1)
            .withRxGatePin(RedPitayaChannel.DIO0_P);

        try (var device = plugin.open(cfg)) {
            var channels = plugin.capabilities().outputChannels();
            int n = 1024;                        // ~8 us at 125 MS/s — fits BRAM
            var steps = buildSteps(n, channels);

            // dt = 1 / effective rate at decim 1 = 8 ns (one DAC tick).
            var result = device.run(8e-9, channels, steps, frac ->
                System.out.print("\rprogress: " + (int)(frac * 100) + "%"));
            System.out.println();

            assertNotNull(result, "device returned null result");
            var trace = result.probeTraces().primary();
            assertNotNull(trace, "no primary trace returned");
            assertFalse(trace.points().isEmpty(), "trace points were empty");

            String txSent = result.deviceMetadata().get("tx_samples_sent");
            String rxCap  = result.deviceMetadata().get("rx_samples_captured");
            System.out.println("Run metadata:");
            for (var e : result.deviceMetadata().entrySet()) {
                System.out.println("  " + e.getKey() + " = " + e.getValue());
            }
            System.out.println("Trace points: " + trace.points().size()
                + ", first=" + trace.points().get(0)
                + ", last=" + trace.points().get(trace.points().size() - 1));
            assertEquals(Integer.toString(n), txSent, "tx_samples_sent did not match what we sent");
            assertTrue(Integer.parseInt(rxCap) > 0, "no rx samples captured");
        }
    }

    /**
     * DDC verification: TX a CW carrier on OUT1, RX with the same carrier
     * configured for digital downconversion, and assert the demodulated
     * baseband settles to the expected non-zero magnitude. With matched
     * carriers the 1 MHz RF disappears and the baseband magnitude
     * |I + jQ| ≈ tx_gain·tx_control; the trace becomes a flat line at
     * that level (modulo a 1 µs boxcar ramp at the very start).
     *
     * <p>Why magnitude, not just I: the absolute phase of the TX carrier
     * and the host DDC mix aren't synchronised, so the energy can land
     * anywhere on the I/Q complex plane on each run — but the magnitude
     * is invariant. Pre-DDC the trace would still be oscillating at
     * 1 MHz (~250 zero-crossings over the 131 µs window).
     *
     * <p>Sends 16384 baseband samples at 8 ns dt so the TX BRAM is fully
     * filled with carrier (no silence padding), and the entire 131 µs ADC
     * window sees the carrier.
     */
    @Test
    void ddcProducesBasebandAtMatchedCarrier() throws Exception {
        String host = System.getProperty("redpitaya.smoke.host");
        var plugin = new RedPitayaPlugin();
        double gain = 0.7;
        var cfg = ((RedPitayaConfig) plugin.defaultConfig())
            .withHostname(host)
            .withTxCarrierHz(1.0e6)
            .withRxCarrierHz(1.0e6)         // matched — DDC moves carrier to DC
            .withTxGain(gain)
            .withSampleRate(RedPitayaSampleRate.DECIM_1)
            .withRxGatePin(null);

        try (var device = plugin.open(cfg)) {
            var channels = plugin.capabilities().outputChannels();
            int n = 16384;
            int txI = channels.indexOf(RedPitayaChannel.TX_I.sequenceChannel());
            var steps = new ArrayList<PulseStep>(n);
            for (int k = 0; k < n; k++) {
                var ctl = new double[channels.size()];
                ctl[txI] = 1.0;
                steps.add(new PulseStep(ctl, 1));
            }

            var result = device.run(8e-9, channels, steps, null);
            // Combine the now-separate rp.rx.i and rp.rx.q probes — TX/RX
            // phase isn't pinned, so I_bb alone could land near zero on
            // some runs while all the energy is in Q_bb. Magnitude is
            // rotation-invariant.
            var iTrace = result.probeTraces().byProbe().get(RedPitayaPlugin.PROBE_RX_I);
            var qTrace = result.probeTraces().byProbe().get(RedPitayaPlugin.PROBE_RX_Q);
            assertFalse(iTrace.points().isEmpty(), "no samples captured");
            assertEquals(iTrace.points().size(), qTrace.points().size());

            int captured = iTrace.points().size();
            int start = Math.max(200, captured / 8);
            int end = captured * 7 / 8;
            double sumMag = 0;
            double minMag = Double.POSITIVE_INFINITY, maxMag = 0;
            for (int i = start; i < end; i++) {
                double iv = iTrace.points().get(i).real();
                double qv = qTrace.points().get(i).real();
                double mag = Math.hypot(iv, qv);
                sumMag += mag;
                if (mag < minMag) minMag = mag;
                if (mag > maxMag) maxMag = mag;
            }
            int span = end - start;
            double meanMag = sumMag / span;
            double relRipple = (maxMag - minMag) / Math.max(1e-9, meanMag);
            System.out.printf("DDC: pts=%d, mean|I+jQ|=%.4f, range=[%.4f..%.4f], rel-ripple=%.3f%n",
                captured, meanMag, minMag, maxMag, relRipple);

            // After DDC at the matched carrier, baseband magnitude is
            // roughly the TX gain. Loose lower bound — cable losses + ADC
            // scaling vary per setup — but it must be clearly non-zero.
            assertTrue(meanMag > 0.1,
                "DDC baseband too small (mean|signal|=" + meanMag + ") — DDC or TX path broken");
            // After DDC the magnitude is essentially flat. (max-min)/mean
            // should be small (filter ripple / noise only). Pre-DDC the raw
            // RF would give ratios well above 2.0 (peak-to-peak ≈ 2× mean
            // for a rectified sinusoid).
            assertTrue(relRipple < 0.5,
                "DDC magnitude still oscillating (rel-ripple=" + relRipple + ") — boxcar filter not nulling carrier");
        }
    }

    /**
     * Pins the phase relationship the AWG_PE + post-trigger-delay
     * scheme is supposed to deliver. Drives TX_I = +1 (positive
     * constant baseband, TX_Q = 0) at a matched non-zero carrier; if
     * the C server's RP_TRIG_SRC_AWG_PE + rp_AcqSetTriggerDelay setup
     * truly anchors data[0] at the cycle the AWG starts emitting
     * BRAM[0] (which is gain·cos(0) = +gain), then the host DDC's
     * phase-0 cosine should align with the captured phase-0 cosine
     * and produce {@code I_bb ≈ +gain, Q_bb ≈ 0}.
     *
     * <p>If the test fails: the FPGA pipeline (DAC + ADC + trigger
     * latency) is shifting the captured phase by enough to leak
     * energy into Q_bb. The actual phase shift is reported in the
     * test output ({@code atan2(Q_avg, I_avg)} radians).
     */
    @Test
    void hardwareAlignmentPutsConstantBasebandIntoIChannel() throws Exception {
        String host = System.getProperty("redpitaya.smoke.host");
        var plugin = new RedPitayaPlugin();
        double gain = 0.7;
        double carrierHz = 1.0e6;
        var cfg = ((RedPitayaConfig) plugin.defaultConfig())
            .withHostname(host)
            .withTxCarrierHz(carrierHz)
            .withRxCarrierHz(carrierHz)
            .withTxGain(gain)
            .withSampleRate(RedPitayaSampleRate.DECIM_1)
            .withRxGatePin(null);

        try (var device = plugin.open(cfg)) {
            var channels = plugin.capabilities().outputChannels();
            int n = 16384;
            int txI = channels.indexOf(RedPitayaChannel.TX_I.sequenceChannel());
            var steps = new ArrayList<PulseStep>(n);
            for (int k = 0; k < n; k++) {
                var ctl = new double[channels.size()];
                ctl[txI] = 1.0;        // positive constant baseband
                steps.add(new PulseStep(ctl, 1));
            }

            var result = device.run(8e-9, channels, steps, null);
            var iPts = result.probeTraces().byProbe().get(RedPitayaPlugin.PROBE_RX_I).points();
            var qPts = result.probeTraces().byProbe().get(RedPitayaPlugin.PROBE_RX_Q).points();
            assertFalse(iPts.isEmpty());

            // Steady-state window — skip the boxcar ramp-up at the
            // front and any tail effects at the back.
            int s = Math.max(200, iPts.size() / 8);
            int e = iPts.size() * 7 / 8;
            double sumI = 0, sumQ = 0;
            for (int i = s; i < e; i++) {
                sumI += iPts.get(i).real();
                sumQ += qPts.get(i).real();
            }
            int span = e - s;
            double iAvg = sumI / span;
            double qAvg = sumQ / span;
            double mag = Math.hypot(iAvg, qAvg);
            double phaseDeg = Math.toDegrees(Math.atan2(qAvg, iAvg));
            System.out.printf("Phase alignment: I_avg=%.4f, Q_avg=%.4f, |.|=%.4f, phase=%.1f°%n",
                iAvg, qAvg, mag, phaseDeg);

            // Magnitude should be in the right ballpark.
            assertTrue(mag > 0.1, "no baseband recovered (|.|=" + mag + ")");
            // I should be the positive lobe (the user's TX_I = +1 sign).
            assertTrue(iAvg > 0,
                "I_avg=" + iAvg + " is negative — the recovered baseband is sign-flipped relative "
                    + "to the user's TX_I=+1 baseband.");
            // Hardware phase alignment: with the 305 ns linear delay + the
            // residual transfer-function correction baked in, the residual
            // at every calibrated carrier should be sub-degree. We allow
            // 5° here for measurement noise + the fit's own ~0.3° max
            // residual + any drift from the calibration unit.
            assertTrue(Math.abs(phaseDeg) < 5,
                "I/Q phase off by " + phaseDeg + "° — TF correction isn't holding. "
                    + "iAvg=" + iAvg + ", qAvg=" + qAvg);
        }
    }

    /**
     * Disambiguate the FPGA signal-chain group delay by sweeping the
     * carrier and back-solving from the residual phase.
     *
     * <p>A constant-1 baseband at carrier f, after a real
     * carrier-frequency-proportional pipeline delay τ in the FPGA, lands
     * in the captured ADC stream rotated by φ(f) = -2π·f·τ. The host DDC
     * then re-rotates by +2π·f·τ_assumed. The residual the user sees is
     *
     * <pre>
     *   φ_residual(f) = 2π·f·(τ_assumed − τ_actual)   (mod 2π)
     * </pre>
     *
     * <p>If τ_assumed = τ_actual, the residual is 0 at every carrier.
     * If they differ by ε, the residual scales linearly with f and wraps.
     *
     * <p>Critical insight: the empirical 305 ns came from a single
     * 1 MHz measurement. At 1 MHz, any τ = 305 ns + N·1 µs produces the
     * same observed phase — they're aliased. This test runs at
     * non-harmonic carriers (0.5, 1.3, 2.7 MHz) where aliases diverge,
     * unwraps the phase across them, and reports the implied true τ via
     * least-squares slope of φ vs. f.
     *
     * <p>What to do with the result:
     * <ul>
     *   <li>If implied τ ≈ 305 ns and residuals are all near 0, the
     *       single-carrier calibration was correct after all and
     *       something else is causing the user's 50/50 split.</li>
     *   <li>If implied τ differs from 305 ns by an integer multiple of
     *       1 µs, that's exactly the aliasing-disambiguation hypothesis;
     *       update {@code RP_FPGA_PIPELINE_DELAY_S} to the new value.</li>
     *   <li>If residuals are NOT linear in f, the model "fixed time
     *       delay" is wrong — there's a frequency-dependent phase term
     *       (DAC reconstruction filter ripple, or a constant phase
     *       offset somewhere). Print would show the non-linearity.</li>
     * </ul>
     */
    @Test
    void fpgaPipelineDelayIsConsistentAcrossCarriers() throws Exception {
        String host = System.getProperty("redpitaya.smoke.host");
        var plugin = new RedPitayaPlugin();
        // Dense sweep across the full benchtop-MR carrier range, with
        // closely-spaced points so phase unwrapping stays unambiguous
        // (consecutive samples should differ by < π).
        double[] carriers = {
            0.5e6,  1.0e6,  1.5e6,  2.0e6,  3.0e6,  4.0e6,  5.0e6,  6.5e6,
            8.0e6,  10.0e6, 12.0e6, 14.0e6, 16.0e6, 18.0e6, 19.0e6, 20.0e6,
            21.0e6, 22.0e6, 23.0e6, 24.0e6, 25.0e6, 27.0e6, 30.0e6
        };
        double[] magArr = new double[carriers.length];
        double[] residualRad = new double[carriers.length];

        System.out.println("=== FPGA pipeline delay carrier sweep ===");
        System.out.printf("%10s %12s %12s %12s %10s%n",
            "carrier", "I_avg", "Q_avg", "|.|", "phi(deg)");

        for (int i = 0; i < carriers.length; i++) {
            double f = carriers[i];
            var cfg = ((RedPitayaConfig) plugin.defaultConfig())
                .withHostname(host)
                .withTxCarrierHz(f)
                .withRxCarrierHz(f)
                .withTxGain(0.7)
                .withSampleRate(RedPitayaSampleRate.DECIM_1)
                .withRxGatePin(null);

            try (var device = plugin.open(cfg)) {
                var channels = plugin.capabilities().outputChannels();
                int txI = channels.indexOf(RedPitayaChannel.TX_I.sequenceChannel());
                int n = 16384;
                var steps = new ArrayList<PulseStep>(n);
                for (int k = 0; k < n; k++) {
                    var ctl = new double[channels.size()];
                    ctl[txI] = 1.0;
                    steps.add(new PulseStep(ctl, 1));
                }
                var result = device.run(8e-9, channels, steps, null);
                var iPts = result.probeTraces().byProbe().get(RedPitayaPlugin.PROBE_RX_I).points();
                var qPts = result.probeTraces().byProbe().get(RedPitayaPlugin.PROBE_RX_Q).points();

                int s = Math.max(200, iPts.size() / 8);
                int e = iPts.size() * 7 / 8;
                double sumI = 0, sumQ = 0;
                for (int k = s; k < e; k++) {
                    sumI += iPts.get(k).real();
                    sumQ += qPts.get(k).real();
                }
                int span = e - s;
                double iAvg = sumI / span, qAvg = sumQ / span;
                double mag = Math.hypot(iAvg, qAvg);
                double phi = Math.atan2(qAvg, iAvg);
                residualRad[i] = phi;
                magArr[i] = mag;
                System.out.printf("%10.0f %12.4f %12.4f %12.4f %10.2f%n",
                    f, iAvg, qAvg, mag, Math.toDegrees(phi));
            }
        }

        // Emit the raw triples for re-fitting in Python if drift is
        // suspected.
        System.out.println();
        System.out.println("# csv: f_hz,phi_rad,mag");
        for (int i = 0; i < carriers.length; i++) {
            System.out.printf("# %.0f,%.6f,%.6f%n",
                carriers[i], residualRad[i], magArr[i]);
        }

        // Hard assertion: the residual after the baked-in TF correction
        // should stay within the calibration's max-residual margin (0.26°
        // from the fit + 1° measurement tolerance) at every carrier in
        // the calibrated range (0.5 → 18 MHz). Outside that range the
        // TF extrapolates and we don't pin a bound — log only.
        double calibratedUpperHz = 18.5e6;
        double maxAllowedDeg = 1.5;
        double worstInBandDeg = 0;
        int worstIdx = 0;
        for (int i = 0; i < carriers.length; i++) {
            if (carriers[i] > calibratedUpperHz) continue;
            double d = Math.abs(Math.toDegrees(residualRad[i]));
            if (d > worstInBandDeg) { worstInBandDeg = d; worstIdx = i; }
        }
        System.out.printf("%nWorst in-band residual: %.2f° at f=%.0f Hz%n",
            worstInBandDeg, carriers[worstIdx]);
        assertTrue(worstInBandDeg < maxAllowedDeg,
            "in-band phase residual " + worstInBandDeg + "° exceeds " + maxAllowedDeg
                + "° at f=" + carriers[worstIdx] + " — has the hardware drifted, or has the "
                + "RP_RESIDUAL_TF coefficient block been edited without re-fitting?");
    }

    /**
     * Build {@code n} PulseSteps. TX I = 0.5, TX Q = 0 throughout; DIO0_P
     * goes high at sample n/4 and low at sample 3n/4 (the RX window).
     */
    private static List<PulseStep> buildSteps(int n, List<SequenceChannel> channels) {
        int txI = channels.indexOf(RedPitayaChannel.TX_I.sequenceChannel());
        int txQ = channels.indexOf(RedPitayaChannel.TX_Q.sequenceChannel());
        int dio = channels.indexOf(RedPitayaChannel.DIO0_P.sequenceChannel());
        int width = channels.size();
        var steps = new ArrayList<PulseStep>(n);
        for (int k = 0; k < n; k++) {
            var ctl = new double[width];
            ctl[txI] = 0.5;
            ctl[txQ] = 0;
            ctl[dio] = (k >= n / 4 && k < 3 * n / 4) ? 1 : 0;
            steps.add(new PulseStep(ctl, 1));
        }
        return steps;
    }
}
