package ax.xz.mri.model.simulation;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A named route from a DAW-authored sequence channel into a physical source.
 *
 * <p>A {@link DrivePath} sits between the user's timeline and the hardware:
 * the clip baker aggregates clips into a per-step amplitude for this path,
 * and at simulation time that amplitude is either
 * <ul>
 *   <li>scaled by the target {@linkplain #transmitCoilName() transmit coil}'s
 *       eigenfield to produce a B-field contribution — the usual case, with
 *       {@link AmplitudeKind#REAL} or {@link AmplitudeKind#QUADRATURE}; or</li>
 *   <li>interpreted as a digital gate signal (0 / 1) with
 *       {@link AmplitudeKind#GATE} — driving a T/R switch, an ADC window, or
 *       any other gated consumer.</li>
 * </ul>
 *
 * <p>For gate paths, {@link #transmitCoilName()} is ignored (may be {@code null}).
 * Gate paths consume no physical B-field; their amplitude simply flows as an
 * observable control signal.
 *
 * <p>{@link #gateInputName()} (optional) references another drive path —
 * typically one with {@link AmplitudeKind#GATE} — whose value gates this
 * path's contribution. Zero gate → contribution is muted; non-zero →
 * contribution flows with its authored amplitude. This is the explicit T/R
 * switch: instead of inferring "RF off = acquire," the user wires a gate
 * to the RX coils and TX paths explicitly.
 */
public record DrivePath(
    String name,
    @JsonProperty("transmit_coil_name") String transmitCoilName,
    AmplitudeKind kind,
    @JsonProperty("carrier_hz") double carrierHz,
    @JsonProperty("min_amplitude") double minAmplitude,
    @JsonProperty("max_amplitude") double maxAmplitude,
    @JsonProperty("gate_input_name") String gateInputName
) {
    public DrivePath {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("DrivePath.name must be non-blank");
        if (kind == null) kind = AmplitudeKind.REAL;
        if (kind == AmplitudeKind.GATE && !(Double.isFinite(minAmplitude) && minAmplitude >= 0))
            throw new IllegalArgumentException("GATE path must have non-negative minAmplitude, got " + minAmplitude);
        if (kind != AmplitudeKind.GATE && (transmitCoilName == null || transmitCoilName.isBlank()))
            throw new IllegalArgumentException("Non-gate DrivePath '" + name + "' must reference a transmit coil");
    }

    public int channelCount() {
        return kind.channelCount();
    }

    public boolean isGate() {
        return kind == AmplitudeKind.GATE;
    }

    public DrivePath withName(String newName) {
        return new DrivePath(newName, transmitCoilName, kind, carrierHz, minAmplitude, maxAmplitude, gateInputName);
    }

    public DrivePath withTransmitCoilName(String newCoil) {
        return new DrivePath(name, newCoil, kind, carrierHz, minAmplitude, maxAmplitude, gateInputName);
    }

    public DrivePath withKind(AmplitudeKind newKind) {
        return new DrivePath(name, transmitCoilName, newKind, carrierHz, minAmplitude, maxAmplitude, gateInputName);
    }

    public DrivePath withCarrierHz(double newCarrierHz) {
        return new DrivePath(name, transmitCoilName, kind, newCarrierHz, minAmplitude, maxAmplitude, gateInputName);
    }

    public DrivePath withMinAmplitude(double newMin) {
        return new DrivePath(name, transmitCoilName, kind, carrierHz, newMin, maxAmplitude, gateInputName);
    }

    public DrivePath withMaxAmplitude(double newMax) {
        return new DrivePath(name, transmitCoilName, kind, carrierHz, minAmplitude, newMax, gateInputName);
    }

    public DrivePath withGateInputName(String newGate) {
        return new DrivePath(name, transmitCoilName, kind, carrierHz, minAmplitude, maxAmplitude, newGate);
    }
}
