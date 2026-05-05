package ax.xz.mri.hardware.builtin.redpitaya;

import ax.xz.mri.hardware.HardwareConfig;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent configuration for the {@link RedPitayaPlugin}. Pure immutable
 * record — built by the plugin's {@code defaultConfig()} and edited via
 * {@link ax.xz.mri.hardware.builtin.redpitaya.editor.RedPitayaConfigEditor}.
 *
 * @param hostname            mDNS / DNS host of the on-RP server, e.g. {@code rp-f03e18.local}
 * @param port                TCP port the server listens on (default 6981)
 * @param connectTimeoutMs    socket connect timeout
 * @param txCarrierHz         transmit carrier frequency (Hz)
 * @param rxCarrierHz         receive carrier frequency (Hz); usually equal to TX
 * @param sampleRate          ADC/DAC decimation enum
 * @param txPort              physical RF output port (OUT1/OUT2)
 * @param txGain              0..1 amplitude scale before DAC encoding
 * @param rxGatePin           GPIO pin that gates the ADC capture window;
 *                            {@code null} = capture continuously for the run
 * @param pinLabels           user-facing labels per pin (e.g. {@code DIO0_P} -> "B0 enable")
 * @param diagnosticPollMs    background diagnostics poll interval (ms)
 */
public record RedPitayaConfig(
    @JsonProperty("hostname")            String hostname,
    @JsonProperty("port")                int port,
    @JsonProperty("connect_timeout_ms")  int connectTimeoutMs,
    @JsonProperty("tx_carrier_hz")       double txCarrierHz,
    @JsonProperty("rx_carrier_hz")       double rxCarrierHz,
    @JsonProperty("sample_rate")         RedPitayaSampleRate sampleRate,
    @JsonProperty("tx_output_port")      RedPitayaTxPort txPort,
    @JsonProperty("tx_gain")             double txGain,
    @JsonProperty("rx_gate_pin")         RedPitayaChannel rxGatePin,
    @JsonProperty("pin_labels")          Map<RedPitayaChannel, String> pinLabels,
    @JsonProperty("diagnostic_poll_ms")  int diagnosticPollMs
) implements HardwareConfig {

    public static final String PLUGIN_ID = "ax.xz.mri.redpitaya.stockos";

    public RedPitayaConfig {
        if (hostname == null) hostname = "";
        if (sampleRate == null) sampleRate = RedPitayaSampleRate.DECIM_8;
        if (txPort == null) txPort = RedPitayaTxPort.OUT1;
        pinLabels = pinLabels == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(pinLabels));
    }

    @Override
    public String pluginId() {
        return PLUGIN_ID;
    }

    public RedPitayaConfig withHostname(String v)        { return new RedPitayaConfig(v, port, connectTimeoutMs, txCarrierHz, rxCarrierHz, sampleRate, txPort, txGain, rxGatePin, pinLabels, diagnosticPollMs); }
    public RedPitayaConfig withPort(int v)               { return new RedPitayaConfig(hostname, v, connectTimeoutMs, txCarrierHz, rxCarrierHz, sampleRate, txPort, txGain, rxGatePin, pinLabels, diagnosticPollMs); }
    public RedPitayaConfig withConnectTimeoutMs(int v)   { return new RedPitayaConfig(hostname, port, v, txCarrierHz, rxCarrierHz, sampleRate, txPort, txGain, rxGatePin, pinLabels, diagnosticPollMs); }
    public RedPitayaConfig withTxCarrierHz(double v)     { return new RedPitayaConfig(hostname, port, connectTimeoutMs, v, rxCarrierHz, sampleRate, txPort, txGain, rxGatePin, pinLabels, diagnosticPollMs); }
    public RedPitayaConfig withRxCarrierHz(double v)     { return new RedPitayaConfig(hostname, port, connectTimeoutMs, txCarrierHz, v, sampleRate, txPort, txGain, rxGatePin, pinLabels, diagnosticPollMs); }
    public RedPitayaConfig withSampleRate(RedPitayaSampleRate v) { return new RedPitayaConfig(hostname, port, connectTimeoutMs, txCarrierHz, rxCarrierHz, v, txPort, txGain, rxGatePin, pinLabels, diagnosticPollMs); }
    public RedPitayaConfig withTxPort(RedPitayaTxPort v) { return new RedPitayaConfig(hostname, port, connectTimeoutMs, txCarrierHz, rxCarrierHz, sampleRate, v, txGain, rxGatePin, pinLabels, diagnosticPollMs); }
    public RedPitayaConfig withTxGain(double v)          { return new RedPitayaConfig(hostname, port, connectTimeoutMs, txCarrierHz, rxCarrierHz, sampleRate, txPort, v, rxGatePin, pinLabels, diagnosticPollMs); }
    public RedPitayaConfig withRxGatePin(RedPitayaChannel v) { return new RedPitayaConfig(hostname, port, connectTimeoutMs, txCarrierHz, rxCarrierHz, sampleRate, txPort, txGain, v, pinLabels, diagnosticPollMs); }
    public RedPitayaConfig withPinLabels(Map<RedPitayaChannel, String> v) { return new RedPitayaConfig(hostname, port, connectTimeoutMs, txCarrierHz, rxCarrierHz, sampleRate, txPort, txGain, rxGatePin, v, diagnosticPollMs); }
    public RedPitayaConfig withDiagnosticPollMs(int v)   { return new RedPitayaConfig(hostname, port, connectTimeoutMs, txCarrierHz, rxCarrierHz, sampleRate, txPort, txGain, rxGatePin, pinLabels, v); }

    public RedPitayaConfig withPinLabel(RedPitayaChannel pin, String label) {
        var next = new LinkedHashMap<>(pinLabels);
        if (label == null || label.isBlank()) next.remove(pin);
        else next.put(pin, label);
        return withPinLabels(next);
    }
}
