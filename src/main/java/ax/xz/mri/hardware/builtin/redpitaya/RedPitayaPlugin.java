package ax.xz.mri.hardware.builtin.redpitaya;

import ax.xz.mri.hardware.HardwareConfig;
import ax.xz.mri.hardware.HardwareConfigEditor;
import ax.xz.mri.hardware.HardwareDevice;
import ax.xz.mri.hardware.HardwareException;
import ax.xz.mri.hardware.HardwarePlugin;
import ax.xz.mri.hardware.builtin.redpitaya.editor.RedPitayaConfigEditor;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/**
 * First real hardware plugin: a Red Pitaya STEMlab 125-14 driven by the
 * companion {@code mri-rp-server} program over TCP. The server runs on the
 * Pitaya under the stock OS and uses {@code librp} streaming for TX/RX with
 * on-ARM carrier mixing.
 *
 * <p>Channel surface, version constants, and capabilities are derived from
 * {@link RedPitayaChannel}; see also {@link RedPitayaConfig} for the
 * persistent configuration record and {@link RedPitayaDevice} for the
 * per-run handle.
 */
public final class RedPitayaPlugin implements HardwarePlugin {

    public static final int CLIENT_PROTO_MAJOR = 1;
    public static final int CLIENT_PROTO_MINOR = 0;

    /** Raw ADC samples — what the device's ADC saw, with the carrier still in place. Real-valued (Q=0). */
    public static final String PROBE_RX = "rp.rx";
    /**
     * In-phase component of the digitally down-converted RX signal. For a
     * non-zero {@link RedPitayaConfig#rxCarrierHz} this is the matched-cosine
     * mix of the raw ADC stream low-pass-filtered to baseband; for
     * carrier=0 the DDC degenerates to identity and this trace equals
     * {@link #PROBE_RX}. Real-valued.
     */
    public static final String PROBE_RX_I = "rp.rx.i";
    /**
     * Quadrature component of the digitally down-converted RX signal. The
     * matched-(-sine) mix at {@link RedPitayaConfig#rxCarrierHz}; for
     * carrier=0 it's identically zero. Real-valued.
     */
    public static final String PROBE_RX_Q = "rp.rx.q";

    private static final Capabilities CAPABILITIES = new Capabilities(
        RedPitayaChannel.capabilityChannels(),
        java.util.List.of(PROBE_RX, PROBE_RX_I, PROBE_RX_Q),
        RedPitayaSampleRate.DECIM_1.effectiveRateHz(),
        RedPitayaSampleRate.DECIM_1.minDtSeconds()
    );

    @Override public String id() { return RedPitayaConfig.PLUGIN_ID; }
    @Override public String displayName() { return "Red Pitaya (stock OS)"; }
    @Override public String description() {
        return "Red Pitaya STEMlab 125-14 driven via mri-rp-server over TCP. "
            + "Software-mode carrier mixing on the ARM CPU using librp streaming; "
            + "no custom FPGA bitstream required.";
    }
    @Override public Capabilities capabilities() { return CAPABILITIES; }

    @Override
    public HardwareConfig defaultConfig() {
        return new RedPitayaConfig(
            "rp-XXXXXX.local",
            6981,
            2000,
            21.3e6,
            21.3e6,
            RedPitayaSampleRate.DECIM_8,
            RedPitayaTxPort.OUT1,
            0.5,
            null,
            Map.of(),
            3000
        );
    }

    @Override
    public HardwareConfigEditor configEditor(HardwareConfig initial) {
        var typed = initial instanceof RedPitayaConfig rp ? rp : (RedPitayaConfig) defaultConfig();
        return new RedPitayaConfigEditor(typed);
    }

    @Override
    public HardwareDevice open(HardwareConfig config) throws HardwareException {
        if (!(config instanceof RedPitayaConfig rp)) {
            throw new HardwareException("RedPitayaPlugin received non-Red-Pitaya config: " + config);
        }
        try {
            var transport = new TcpRedPitayaTransport(rp.hostname(), rp.port(), rp.connectTimeoutMs());
            return new RedPitayaDevice(rp, transport);
        } catch (IOException ex) {
            throw new HardwareException(
                "Could not connect to " + rp.hostname() + ":" + rp.port() + " (" + ex.getMessage() + "). "
                    + "Is mri-rp-server installed and running?", ex);
        }
        // No runtime phase calibration. The C server's RP_TRIG_SRC_AWG_PE
        // trigger pins data[0] to the FPGA cycle the AWG starts emitting
        // BRAM[0], so the host DDC's phase-0 origin lines up with the TX
        // cosine's phase-0 origin in hardware. The only residual is the
        // FPGA signal-chain pipeline (DAC reconstruction filter + ADC
        // equalizer ≈ 305 ns), which the host applies as a fixed
        // carrier-dependent rotation in RedPitayaDevice.buildTraces.
    }

    @Override
    public HardwareConfig deserialiseConfig(JsonNode raw, ObjectMapper mapper) throws JsonMappingException {
        if (raw == null || raw.isNull()) return defaultConfig();
        return mapper.convertValue(raw, RedPitayaConfig.class);
    }
}
