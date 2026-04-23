package ax.xz.mri.model.circuit;

import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.project.ProjectNodeId;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * A component in a {@link CircuitDocument}.
 *
 * <p>Each component has a stable {@link #id()}, a human-readable
 * {@link #name()}, and a canonical set of {@linkplain #ports() terminal
 * names} used by {@link Wire}s. Simulation-relevant data (resistance values,
 * eigenfield references, etc.) lives on the concrete subtype.
 *
 * <p>Every concrete subtype is a {@code record} so the whole circuit model is
 * immutable + structurally comparable + trivially serialisable.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = CircuitComponent.VoltageSource.class, name = "voltage_source"),
    @JsonSubTypes.Type(value = CircuitComponent.SwitchComponent.class, name = "switch"),
    @JsonSubTypes.Type(value = CircuitComponent.Coil.class, name = "coil"),
    @JsonSubTypes.Type(value = CircuitComponent.Probe.class, name = "probe"),
    @JsonSubTypes.Type(value = CircuitComponent.Ground.class, name = "ground"),
    @JsonSubTypes.Type(value = CircuitComponent.Resistor.class, name = "resistor"),
    @JsonSubTypes.Type(value = CircuitComponent.Capacitor.class, name = "capacitor"),
    @JsonSubTypes.Type(value = CircuitComponent.Inductor.class, name = "inductor"),
    @JsonSubTypes.Type(value = CircuitComponent.IdealTransformer.class, name = "transformer")
})
public sealed interface CircuitComponent {
    ComponentId id();
    String name();
    List<String> ports();

    CircuitComponent withName(String newName);

    // ─── Voltage source ───────────────────────────────────────────────────────

    /**
     * A per-step voltage driven by DAW tracks. Every sequence track whose
     * {@link ax.xz.mri.model.sequence.SequenceChannel#sourceName()} equals this
     * source's {@link #name()} sums into it, sample by sample, at bake time
     * ({@link ax.xz.mri.model.sequence.ClipBaker}). The wiring decides where
     * that voltage goes next.
     *
     * <p>{@link #kind()} controls how many sequence-controls scalars flow
     * through this source per step. {@link #outputImpedanceOhms()} is the
     * source's Thevenin impedance — zero for an ideal source.
     */
    record VoltageSource(
        ComponentId id,
        String name,
        AmplitudeKind kind,
        @JsonProperty("carrier_hz") double carrierHz,
        @JsonProperty("min_amplitude") double minAmplitude,
        @JsonProperty("max_amplitude") double maxAmplitude,
        @JsonProperty("output_impedance_ohms") double outputImpedanceOhms
    ) implements CircuitComponent {
        public VoltageSource {
            if (id == null) throw new IllegalArgumentException("VoltageSource.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("VoltageSource.name must be non-blank");
            if (kind == null) throw new IllegalArgumentException("VoltageSource.kind must not be null");
            if (kind == AmplitudeKind.GATE && !(Double.isFinite(minAmplitude) && minAmplitude >= 0))
                throw new IllegalArgumentException("GATE source must have non-negative minAmplitude, got " + minAmplitude);
            if (!(outputImpedanceOhms >= 0) || !Double.isFinite(outputImpedanceOhms))
                throw new IllegalArgumentException("VoltageSource.outputImpedanceOhms must be non-negative, got " + outputImpedanceOhms);
        }

        @Override public List<String> ports() { return List.of("out"); }

        @JsonIgnore
        public int channelCount() { return kind.channelCount(); }

        @JsonIgnore
        public boolean isGate() { return kind == AmplitudeKind.GATE; }

        @Override
        public VoltageSource withName(String newName) {
            return new VoltageSource(id, newName, kind, carrierHz, minAmplitude, maxAmplitude, outputImpedanceOhms);
        }

        public VoltageSource withKind(AmplitudeKind newKind) {
            return new VoltageSource(id, name, newKind, carrierHz, minAmplitude, maxAmplitude, outputImpedanceOhms);
        }

        public VoltageSource withCarrierHz(double v) {
            return new VoltageSource(id, name, kind, v, minAmplitude, maxAmplitude, outputImpedanceOhms);
        }

        public VoltageSource withMinAmplitude(double v) {
            return new VoltageSource(id, name, kind, carrierHz, v, maxAmplitude, outputImpedanceOhms);
        }

        public VoltageSource withMaxAmplitude(double v) {
            return new VoltageSource(id, name, kind, carrierHz, minAmplitude, v, outputImpedanceOhms);
        }

        public VoltageSource withOutputImpedanceOhms(double v) {
            return new VoltageSource(id, name, kind, carrierHz, minAmplitude, maxAmplitude, v);
        }
    }

    // ─── Switch ───────────────────────────────────────────────────────────────

    /**
     * A controlled two-terminal switch. The {@code ctl} port reads a scalar
     * voltage; above {@link #thresholdVolts()} the switch is closed (low
     * impedance), below it is open (high impedance). Wire {@code ctl} to a
     * {@link VoltageSource} with {@link AmplitudeKind#GATE} to get the
     * classic T/R switch.
     */
    record SwitchComponent(
        ComponentId id,
        String name,
        @JsonProperty("closed_ohms") double closedOhms,
        @JsonProperty("open_ohms") double openOhms,
        @JsonProperty("threshold_volts") double thresholdVolts
    ) implements CircuitComponent {
        public SwitchComponent {
            if (id == null) throw new IllegalArgumentException("Switch.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Switch.name must be non-blank");
            if (!(closedOhms > 0)) throw new IllegalArgumentException("Switch.closedOhms must be > 0");
            if (!(openOhms > closedOhms)) throw new IllegalArgumentException("Switch.openOhms must exceed closedOhms");
        }

        @Override public List<String> ports() { return List.of("a", "b", "ctl"); }

        @Override
        public SwitchComponent withName(String newName) {
            return new SwitchComponent(id, newName, closedOhms, openOhms, thresholdVolts);
        }
    }

    // ─── Coil ─────────────────────────────────────────────────────────────────

    /**
     * A physical coil — the bridge between the circuit and the FOV. Carries
     * an {@linkplain #eigenfieldId() eigenfield} describing the B-field
     * shape at unit current. The current through the coil produces a B
     * contribution; the magnetisation's time-derivative (reciprocity)
     * contributes an EMF between the coil's terminals.
     */
    record Coil(
        ComponentId id,
        String name,
        @JsonProperty("eigenfield_id") ProjectNodeId eigenfieldId,
        @JsonProperty("self_inductance_henry") double selfInductanceHenry,
        @JsonProperty("series_resistance_ohms") double seriesResistanceOhms
    ) implements CircuitComponent {
        public Coil {
            if (id == null) throw new IllegalArgumentException("Coil.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Coil.name must be non-blank");
            if (!(selfInductanceHenry >= 0) || !Double.isFinite(selfInductanceHenry))
                throw new IllegalArgumentException("Coil.selfInductanceHenry must be finite non-negative");
            if (!(seriesResistanceOhms >= 0) || !Double.isFinite(seriesResistanceOhms))
                throw new IllegalArgumentException("Coil.seriesResistanceOhms must be finite non-negative");
        }

        @Override public List<String> ports() { return List.of("a", "b"); }

        @Override
        public Coil withName(String newName) {
            return new Coil(id, newName, eigenfieldId, selfInductanceHenry, seriesResistanceOhms);
        }

        public Coil withEigenfieldId(ProjectNodeId newId) {
            return new Coil(id, name, newId, selfInductanceHenry, seriesResistanceOhms);
        }

        public Coil withSelfInductanceHenry(double v) {
            return new Coil(id, name, eigenfieldId, v, seriesResistanceOhms);
        }

        public Coil withSeriesResistanceOhms(double v) {
            return new Coil(id, name, eigenfieldId, selfInductanceHenry, v);
        }
    }

    // ─── Probe ────────────────────────────────────────────────────────────────

    /**
     * A voltage measurement point (versus ground). Emits one
     * {@link ax.xz.mri.model.simulation.SignalTrace} per simulation.
     *
     * <p>{@link #demodPhaseDeg()} rotates the reported {@code (real, imag)}
     * complex signal by the given phase. {@link #gain()} scales the result.
     * {@link #loadImpedanceOhms()} (default {@link Double#POSITIVE_INFINITY})
     * is the probe's input impedance — an ideal voltmeter has infinite load.
     *
     * <p>Single-terminal: the probe's input side wires into the circuit; the
     * other pole is the implicit ground.
     */
    record Probe(
        ComponentId id,
        String name,
        double gain,
        @JsonProperty("demod_phase_deg") double demodPhaseDeg,
        @JsonProperty("load_impedance_ohms") double loadImpedanceOhms
    ) implements CircuitComponent {
        public Probe {
            if (id == null) throw new IllegalArgumentException("Probe.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Probe.name must be non-blank");
            if (!Double.isFinite(gain)) throw new IllegalArgumentException("Probe.gain must be finite");
            if (!Double.isFinite(demodPhaseDeg)) throw new IllegalArgumentException("Probe.demodPhaseDeg must be finite");
            if (!(loadImpedanceOhms > 0)) throw new IllegalArgumentException("Probe.loadImpedanceOhms must be > 0");
        }

        @Override public List<String> ports() { return List.of("in"); }

        @Override
        public Probe withName(String newName) {
            return new Probe(id, newName, gain, demodPhaseDeg, loadImpedanceOhms);
        }

        public Probe withGain(double v) {
            return new Probe(id, name, v, demodPhaseDeg, loadImpedanceOhms);
        }

        public Probe withDemodPhaseDeg(double v) {
            return new Probe(id, name, gain, v, loadImpedanceOhms);
        }

        public Probe withLoadImpedanceOhms(double v) {
            return new Probe(id, name, gain, demodPhaseDeg, v);
        }
    }

    // ─── Ground ───────────────────────────────────────────────────────────────

    /** The reference node. A circuit must contain at least one. */
    record Ground(ComponentId id, String name) implements CircuitComponent {
        public Ground {
            if (id == null) throw new IllegalArgumentException("Ground.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Ground.name must be non-blank");
        }

        @Override public List<String> ports() { return List.of("a"); }

        @Override
        public Ground withName(String newName) { return new Ground(id, newName); }
    }

    // ─── Passives ─────────────────────────────────────────────────────────────

    record Resistor(ComponentId id, String name, @JsonProperty("resistance_ohms") double resistanceOhms) implements CircuitComponent {
        public Resistor {
            if (id == null) throw new IllegalArgumentException("Resistor.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Resistor.name must be non-blank");
            if (!(resistanceOhms > 0)) throw new IllegalArgumentException("Resistor.resistanceOhms must be > 0");
        }

        @Override public List<String> ports() { return List.of("a", "b"); }

        @Override
        public Resistor withName(String newName) { return new Resistor(id, newName, resistanceOhms); }

        public Resistor withResistanceOhms(double v) { return new Resistor(id, name, v); }
    }

    record Capacitor(ComponentId id, String name, @JsonProperty("capacitance_farads") double capacitanceFarads) implements CircuitComponent {
        public Capacitor {
            if (id == null) throw new IllegalArgumentException("Capacitor.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Capacitor.name must be non-blank");
            if (!(capacitanceFarads > 0)) throw new IllegalArgumentException("Capacitor.capacitanceFarads must be > 0");
        }

        @Override public List<String> ports() { return List.of("a", "b"); }

        @Override
        public Capacitor withName(String newName) { return new Capacitor(id, newName, capacitanceFarads); }

        public Capacitor withCapacitanceFarads(double v) { return new Capacitor(id, name, v); }
    }

    record Inductor(ComponentId id, String name, @JsonProperty("inductance_henry") double inductanceHenry) implements CircuitComponent {
        public Inductor {
            if (id == null) throw new IllegalArgumentException("Inductor.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Inductor.name must be non-blank");
            if (!(inductanceHenry > 0)) throw new IllegalArgumentException("Inductor.inductanceHenry must be > 0");
        }

        @Override public List<String> ports() { return List.of("a", "b"); }

        @Override
        public Inductor withName(String newName) { return new Inductor(id, newName, inductanceHenry); }

        public Inductor withInductanceHenry(double v) { return new Inductor(id, name, v); }
    }

    /** Ideal two-port transformer with ports {@code pa}, {@code pb} (primary) and {@code sa}, {@code sb} (secondary). */
    record IdealTransformer(ComponentId id, String name, @JsonProperty("turns_ratio") double turnsRatio) implements CircuitComponent {
        public IdealTransformer {
            if (id == null) throw new IllegalArgumentException("IdealTransformer.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("IdealTransformer.name must be non-blank");
            if (!(Math.abs(turnsRatio) > 0) || !Double.isFinite(turnsRatio))
                throw new IllegalArgumentException("IdealTransformer.turnsRatio must be finite non-zero");
        }

        @Override public List<String> ports() { return List.of("pa", "pb", "sa", "sb"); }

        @Override
        public IdealTransformer withName(String newName) { return new IdealTransformer(id, newName, turnsRatio); }

        public IdealTransformer withTurnsRatio(double v) { return new IdealTransformer(id, name, v); }
    }
}
