package ax.xz.mri.model.circuit;

import ax.xz.mri.model.circuit.compile.CircuitStampContext;
import ax.xz.mri.model.circuit.compile.ComplexPairFormat;
import ax.xz.mri.model.circuit.compile.SwitchParams;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.project.ProjectNodeId;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
 *
 * <h2>Behaviour, not just data</h2>
 * Each component owns its own simulation behaviour — no compiler-side switch
 * enumerating concrete types:
 * <ul>
 *   <li>{@link #stamp(CircuitStampContext)} writes the component's
 *       contribution to the MNA (resistor / capacitor / switch / mixer
 *       stamps, or {@code registerSource} / {@code registerCoil} /
 *       {@code registerProbe} for first-class simulator entities).</li>
 *   <li>{@link #withId(ComponentId)} returns a clone with a fresh id,
 *       consumed by the schematic's duplicate / cut-paste actions — saves
 *       the UI from having to know every record's field list.</li>
 * </ul>
 *
 * Adding a new kind of component is, on the simulation side, a single
 * {@code record} with those two methods.
 *
 * <h2>Why keep the typed {@code with*()} methods?</h2>
 * Each record has typed wither methods (e.g. {@code withGain}, {@code withClosedOhms})
 * that look like boilerplate. Don't replace them with reflection or a
 * generic builder — Jackson's record-based deserialisation depends on the
 * canonical constructors, and a reflective wither would break Janino's
 * sandboxed compilation environment used for eigenfield scripts. The withers
 * are also each called by exactly one Inspector presenter, where the typed
 * shape gives us compile-time safety; the trade is intentional.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = CircuitComponent.VoltageSource.class, name = "voltage_source"),
    @JsonSubTypes.Type(value = CircuitComponent.SwitchComponent.class, name = "switch"),
    @JsonSubTypes.Type(value = CircuitComponent.Multiplexer.class, name = "multiplexer"),
    @JsonSubTypes.Type(value = CircuitComponent.Coil.class, name = "coil"),
    @JsonSubTypes.Type(value = CircuitComponent.Probe.class, name = "probe"),
    @JsonSubTypes.Type(value = CircuitComponent.Resistor.class, name = "resistor"),
    @JsonSubTypes.Type(value = CircuitComponent.Capacitor.class, name = "capacitor"),
    @JsonSubTypes.Type(value = CircuitComponent.Inductor.class, name = "inductor"),
    @JsonSubTypes.Type(value = CircuitComponent.ShuntResistor.class, name = "shunt_resistor"),
    @JsonSubTypes.Type(value = CircuitComponent.ShuntCapacitor.class, name = "shunt_capacitor"),
    @JsonSubTypes.Type(value = CircuitComponent.ShuntInductor.class, name = "shunt_inductor"),
    @JsonSubTypes.Type(value = CircuitComponent.IdealTransformer.class, name = "transformer"),
    @JsonSubTypes.Type(value = CircuitComponent.Mixer.class, name = "mixer"),
    @JsonSubTypes.Type(value = CircuitComponent.Modulator.class, name = "modulator"),
    @JsonSubTypes.Type(value = CircuitComponent.VoltageMetadata.class, name = "voltage_metadata")
})
public sealed interface CircuitComponent {
    ComponentId id();
    String name();
    List<String> ports();

    CircuitComponent withName(String newName);
    CircuitComponent withId(ComponentId newId);

    /** Contribute this component's stamps to the MNA. */
    void stamp(CircuitStampContext ctx);

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
        double carrierHz,
        double minAmplitude,
        double maxAmplitude,
        double outputImpedanceOhms
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

        /**
         * Single output port: {@code out} carries the scheduled voltage.
         *
         * <p>Derived signals like "is this source's clip playing" live on
         * dedicated {@link VoltageMetadata} blocks — wire a {@code source}
         * input on the metadata block to the source's {@code out}, then
         * read the metadata block's output into a switch's {@code ctl}.
         */
        @Override public List<String> ports() { return List.of("out"); }

        @JsonIgnore
        public int channelCount() { return kind.channelCount(); }

        @JsonIgnore
        public boolean isGate() { return kind == AmplitudeKind.GATE; }

        @Override
        public VoltageSource withName(String newName) {
            return new VoltageSource(id, newName, kind, carrierHz, minAmplitude, maxAmplitude, outputImpedanceOhms);
        }

        @Override
        public VoltageSource withId(ComponentId newId) {
            return new VoltageSource(newId, name, kind, carrierHz, minAmplitude, maxAmplitude, outputImpedanceOhms);
        }

        @Override
        public void stamp(CircuitStampContext ctx) {
            ctx.registerSource(id, name, kind, carrierHz, maxAmplitude, ctx.port("out"));
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
        double closedOhms,
        double openOhms,
        double thresholdVolts,
        boolean invertCtl
    ) implements CircuitComponent {
        public SwitchComponent {
            if (id == null) throw new IllegalArgumentException("Switch.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Switch.name must be non-blank");
            if (!(closedOhms > 0)) throw new IllegalArgumentException("Switch.closedOhms must be > 0");
            if (!(openOhms > closedOhms)) throw new IllegalArgumentException("Switch.openOhms must exceed closedOhms");
        }

        /** Convenience constructor for the common case of a non-inverting switch. */
        public SwitchComponent(ComponentId id, String name, double closedOhms, double openOhms, double thresholdVolts) {
            this(id, name, closedOhms, openOhms, thresholdVolts, false);
        }

        @Override public List<String> ports() { return List.of("a", "b", "ctl"); }

        @Override
        public SwitchComponent withName(String newName) {
            return new SwitchComponent(id, newName, closedOhms, openOhms, thresholdVolts, invertCtl);
        }

        @Override
        public SwitchComponent withId(ComponentId newId) {
            return new SwitchComponent(newId, name, closedOhms, openOhms, thresholdVolts, invertCtl);
        }

        @Override
        public void stamp(CircuitStampContext ctx) {
            var ctl = ctx.resolveCtl(ctx.port("ctl"));
            ctx.stampSwitch(ctx.port("a"), ctx.port("b"),
                SwitchParams.of(closedOhms, openOhms, thresholdVolts, ctl, invertCtl));
        }

        public SwitchComponent withInvertCtl(boolean invert) {
            return new SwitchComponent(id, name, closedOhms, openOhms, thresholdVolts, invert);
        }
    }

    // ─── Multiplexer ──────────────────────────────────────────────────────────

    /**
     * Single-pole double-throw routing element. {@code common} connects to
     * {@code a} when {@code ctl} is high (≥ {@link #thresholdVolts()}) and to
     * {@code b} when it is low. The classic T/R use case: wire the RF source
     * to {@code a}, the probe to {@code b}, the coil to {@code common}, and
     * {@code ctl} to the RF source's {@code active} port — RF drives the coil
     * during TX; the probe observes it during RX. One component does what two
     * opposite-polarity switches would do.
     */
    record Multiplexer(
        ComponentId id,
        String name,
        double closedOhms,
        double openOhms,
        double thresholdVolts
    ) implements CircuitComponent {
        public Multiplexer {
            if (id == null) throw new IllegalArgumentException("Multiplexer.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Multiplexer.name must be non-blank");
            if (!(closedOhms > 0)) throw new IllegalArgumentException("Multiplexer.closedOhms must be > 0");
            if (!(openOhms > closedOhms)) throw new IllegalArgumentException("Multiplexer.openOhms must exceed closedOhms");
        }

        @Override public List<String> ports() { return List.of("a", "b", "common", "ctl"); }

        @Override
        public Multiplexer withName(String newName) {
            return new Multiplexer(id, newName, closedOhms, openOhms, thresholdVolts);
        }

        @Override
        public Multiplexer withId(ComponentId newId) {
            return new Multiplexer(newId, name, closedOhms, openOhms, thresholdVolts);
        }

        @Override
        public void stamp(CircuitStampContext ctx) {
            var ctl = ctx.resolveCtl(ctx.port("ctl"));
            // a ↔ common closes when ctl is high (non-inverting).
            ctx.stampSwitch(ctx.port("a"), ctx.port("common"),
                SwitchParams.of(closedOhms, openOhms, thresholdVolts, ctl, false));
            // b ↔ common closes when ctl is low (inverting).
            ctx.stampSwitch(ctx.port("b"), ctx.port("common"),
                SwitchParams.of(closedOhms, openOhms, thresholdVolts, ctl, true));
        }
    }

    // ─── Coil ─────────────────────────────────────────────────────────────────

    /**
     * A physical coil — the bridge between the circuit and the FOV.
     *
     * <p>Three independent properties:
     * <ul>
     *   <li>{@linkplain #eigenfieldId() shape} — which
     *       {@link ax.xz.mri.project.EigenfieldDocument} the coil produces.
     *       The script is dimensionless (peak |Vec3| ≈ 1 at a reference).</li>
     *   <li>{@link #sensitivityT_per_A} — Tesla per amp. The coil-specific
     *       calibration; a real coil's turns × geometry × material compressed
     *       into one scalar. Peak-field in the FOV for current {@code I} is
     *       {@code I · sensitivity · shape(r)}.</li>
     *   <li>{@link #seriesResistanceOhms} + {@link #selfInductanceHenry} —
     *       the impedance the MNA uses to translate voltage drive into
     *       current. At least one must be positive so the MNA row is
     *       well-posed.</li>
     * </ul>
     *
     * <p>Current into {@code in} produces a B contribution; the
     * magnetisation's time-derivative (reciprocity) induces an EMF at
     * {@code in}. The return side is always ground — coils expose one
     * wireable terminal.
     */
    record Coil(
        ComponentId id,
        String name,
        ProjectNodeId eigenfieldId,
        double selfInductanceHenry,
        double seriesResistanceOhms,
        double sensitivityT_per_A
    ) implements CircuitComponent {
        public Coil {
            if (id == null) throw new IllegalArgumentException("Coil.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Coil.name must be non-blank");
            if (!(selfInductanceHenry >= 0) || !Double.isFinite(selfInductanceHenry))
                throw new IllegalArgumentException("Coil.selfInductanceHenry must be finite non-negative");
            if (!(seriesResistanceOhms >= 0) || !Double.isFinite(seriesResistanceOhms))
                throw new IllegalArgumentException("Coil.seriesResistanceOhms must be finite non-negative");
            if (!Double.isFinite(sensitivityT_per_A))
                throw new IllegalArgumentException("Coil.sensitivityT_per_A must be finite, got " + sensitivityT_per_A);
            if (seriesResistanceOhms == 0 && selfInductanceHenry == 0) {
                throw new IllegalArgumentException(
                    "Coil '" + name + "' needs either seriesResistanceOhms > 0 or " +
                    "selfInductanceHenry > 0 — with both zero, the MNA would have to " +
                    "push infinite current to satisfy any drive voltage. Set one of " +
                    "them in the coil inspector.");
            }
        }

        /** Convenience: defaults {@code sensitivityT_per_A = 1}. */
        public Coil(ComponentId id, String name, ProjectNodeId eigenfieldId,
                    double selfInductanceHenry, double seriesResistanceOhms) {
            this(id, name, eigenfieldId, selfInductanceHenry, seriesResistanceOhms, 1.0);
        }

        @Override public List<String> ports() { return List.of("in"); }

        @Override
        public Coil withName(String newName) {
            return new Coil(id, newName, eigenfieldId, selfInductanceHenry, seriesResistanceOhms, sensitivityT_per_A);
        }

        @Override
        public Coil withId(ComponentId newId) {
            return new Coil(newId, name, eigenfieldId, selfInductanceHenry, seriesResistanceOhms, sensitivityT_per_A);
        }

        @Override
        public void stamp(CircuitStampContext ctx) {
            ctx.registerCoil(id, name, eigenfieldId, selfInductanceHenry, seriesResistanceOhms,
                sensitivityT_per_A, ctx.port("in"));
        }

        public Coil withEigenfieldId(ProjectNodeId newId) {
            return new Coil(id, name, newId, selfInductanceHenry, seriesResistanceOhms, sensitivityT_per_A);
        }

        public Coil withSelfInductanceHenry(double v) {
            return new Coil(id, name, eigenfieldId, v, seriesResistanceOhms, sensitivityT_per_A);
        }

        public Coil withSeriesResistanceOhms(double v) {
            return new Coil(id, name, eigenfieldId, selfInductanceHenry, v, sensitivityT_per_A);
        }

        public Coil withSensitivityT_per_A(double v) {
            return new Coil(id, name, eigenfieldId, selfInductanceHenry, seriesResistanceOhms, v);
        }
    }

    // ─── Probe ────────────────────────────────────────────────────────────────

    /**
     * A voltage measurement point (versus ground). Emits one
     * {@link ax.xz.mri.model.simulation.SignalTrace} per simulation.
     *
     * <p>Probes are pure observers — they read a node voltage in the
     * simulator's rotating frame without loading the circuit. Any frame
     * shifting (down-conversion, up-conversion) happens in a dedicated
     * {@link Mixer} block wired between the circuit and the probe's input.
     * The probe itself only applies a constant {@link #demodPhaseDeg()
     * phase rotation} and a scalar {@link #gain() gain} before emitting
     * {@code (I, Q)} to the trace.
     *
     * <p>Single-terminal: the probe's input side wires into the circuit; the
     * other pole is the implicit ground.
     */
    record Probe(
        ComponentId id,
        String name,
        double gain,
        double demodPhaseDeg,
        double loadImpedanceOhms
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

        @Override
        public Probe withId(ComponentId newId) {
            return new Probe(newId, name, gain, demodPhaseDeg, loadImpedanceOhms);
        }

        @Override
        public void stamp(CircuitStampContext ctx) {
            ctx.registerProbe(id, name, gain, demodPhaseDeg, loadImpedanceOhms,
                ctx.port("in"));
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

    // ─── Passives ─────────────────────────────────────────────────────────────

    record Resistor(ComponentId id, String name, double resistanceOhms) implements CircuitComponent {
        public Resistor {
            if (id == null) throw new IllegalArgumentException("Resistor.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Resistor.name must be non-blank");
            if (!(resistanceOhms > 0)) throw new IllegalArgumentException("Resistor.resistanceOhms must be > 0");
        }

        @Override public List<String> ports() { return List.of("a", "b"); }

        @Override
        public Resistor withName(String newName) { return new Resistor(id, newName, resistanceOhms); }

        @Override
        public Resistor withId(ComponentId newId) { return new Resistor(newId, name, resistanceOhms); }

        @Override
        public void stamp(CircuitStampContext ctx) {
            ctx.stampResistor(ctx.port("a"), ctx.port("b"), resistanceOhms);
        }

        public Resistor withResistanceOhms(double v) { return new Resistor(id, name, v); }
    }

    record Capacitor(ComponentId id, String name, double capacitanceFarads) implements CircuitComponent {
        public Capacitor {
            if (id == null) throw new IllegalArgumentException("Capacitor.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Capacitor.name must be non-blank");
            if (!(capacitanceFarads > 0)) throw new IllegalArgumentException("Capacitor.capacitanceFarads must be > 0");
        }

        @Override public List<String> ports() { return List.of("a", "b"); }

        @Override
        public Capacitor withName(String newName) { return new Capacitor(id, newName, capacitanceFarads); }

        @Override
        public Capacitor withId(ComponentId newId) { return new Capacitor(newId, name, capacitanceFarads); }

        @Override
        public void stamp(CircuitStampContext ctx) {
            ctx.stampCapacitor(ctx.port("a"), ctx.port("b"), capacitanceFarads);
        }

        public Capacitor withCapacitanceFarads(double v) { return new Capacitor(id, name, v); }
    }

    record Inductor(ComponentId id, String name, double inductanceHenry) implements CircuitComponent {
        public Inductor {
            if (id == null) throw new IllegalArgumentException("Inductor.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Inductor.name must be non-blank");
            if (!(inductanceHenry > 0)) throw new IllegalArgumentException("Inductor.inductanceHenry must be > 0");
        }

        @Override public List<String> ports() { return List.of("a", "b"); }

        @Override
        public Inductor withName(String newName) { return new Inductor(id, newName, inductanceHenry); }

        @Override
        public Inductor withId(ComponentId newId) { return new Inductor(newId, name, inductanceHenry); }

        @Override
        public void stamp(CircuitStampContext ctx) {
            ctx.stampInductor(ctx.port("a"), ctx.port("b"), inductanceHenry);
        }

        public Inductor withInductanceHenry(double v) { return new Inductor(id, name, v); }
    }

    // ─── Shunt passives: one terminal, other side is ground ─────────────────

    record ShuntResistor(ComponentId id, String name, double resistanceOhms) implements CircuitComponent {
        public ShuntResistor {
            if (id == null) throw new IllegalArgumentException("ShuntResistor.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("ShuntResistor.name must be non-blank");
            if (!(resistanceOhms > 0)) throw new IllegalArgumentException("ShuntResistor.resistanceOhms must be > 0");
        }
        @Override public List<String> ports() { return List.of("in"); }
        @Override public ShuntResistor withName(String newName) { return new ShuntResistor(id, newName, resistanceOhms); }
        @Override public ShuntResistor withId(ComponentId newId) { return new ShuntResistor(newId, name, resistanceOhms); }
        @Override public void stamp(CircuitStampContext ctx) {
            ctx.stampResistor(ctx.port("in"), ctx.ground(), resistanceOhms);
        }
        public ShuntResistor withResistanceOhms(double v) { return new ShuntResistor(id, name, v); }
    }

    record ShuntCapacitor(ComponentId id, String name, double capacitanceFarads) implements CircuitComponent {
        public ShuntCapacitor {
            if (id == null) throw new IllegalArgumentException("ShuntCapacitor.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("ShuntCapacitor.name must be non-blank");
            if (!(capacitanceFarads > 0)) throw new IllegalArgumentException("ShuntCapacitor.capacitanceFarads must be > 0");
        }
        @Override public List<String> ports() { return List.of("in"); }
        @Override public ShuntCapacitor withName(String newName) { return new ShuntCapacitor(id, newName, capacitanceFarads); }
        @Override public ShuntCapacitor withId(ComponentId newId) { return new ShuntCapacitor(newId, name, capacitanceFarads); }
        @Override public void stamp(CircuitStampContext ctx) {
            ctx.stampCapacitor(ctx.port("in"), ctx.ground(), capacitanceFarads);
        }
        public ShuntCapacitor withCapacitanceFarads(double v) { return new ShuntCapacitor(id, name, v); }
    }

    record ShuntInductor(ComponentId id, String name, double inductanceHenry) implements CircuitComponent {
        public ShuntInductor {
            if (id == null) throw new IllegalArgumentException("ShuntInductor.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("ShuntInductor.name must be non-blank");
            if (!(inductanceHenry > 0)) throw new IllegalArgumentException("ShuntInductor.inductanceHenry must be > 0");
        }
        @Override public List<String> ports() { return List.of("in"); }
        @Override public ShuntInductor withName(String newName) { return new ShuntInductor(id, newName, inductanceHenry); }
        @Override public ShuntInductor withId(ComponentId newId) { return new ShuntInductor(newId, name, inductanceHenry); }
        @Override public void stamp(CircuitStampContext ctx) {
            ctx.stampInductor(ctx.port("in"), ctx.ground(), inductanceHenry);
        }
        public ShuntInductor withInductanceHenry(double v) { return new ShuntInductor(id, name, v); }
    }

    // ─── Mixer (buffered demodulator) ────────────────────────────────────────

    /**
     * Complex-baseband mixer. Senses the voltage at {@code in} with infinite
     * input impedance, frame-shifts it by {@code exp(-j·2π·loHz·t)} in the
     * simulator's rotating frame, and decomposes the result onto two
     * buffered scalar outputs:
     *
     * <ul>
     *   <li>{@link ComplexPairFormat#IQ}: {@code out0} = I (real part),
     *       {@code out1} = Q (imaginary part).</li>
     *   <li>{@link ComplexPairFormat#MAG_PHASE}: {@code out0} =
     *       magnitude, {@code out1} = phase in radians.</li>
     * </ul>
     *
     * <p>Both outputs are true voltage-controlled voltage sources — they
     * can drive arbitrary loads (probes, filters) without affecting
     * {@code in}. Mirror of {@link Modulator}, which takes two scalar
     * inputs and upconverts into a single complex output.
     */
    record Mixer(
        ComponentId id,
        String name,
        double loHz,
        ComplexPairFormat format
    ) implements CircuitComponent {
        public Mixer {
            if (id == null) throw new IllegalArgumentException("Mixer.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Mixer.name must be non-blank");
            if (!Double.isFinite(loHz)) throw new IllegalArgumentException("Mixer.loHz must be finite");
            if (format == null) throw new IllegalArgumentException("Mixer.format must not be null");
        }

        /** Convenience constructor defaulting to {@link ComplexPairFormat#IQ}. */
        public Mixer(ComponentId id, String name, double loHz) {
            this(id, name, loHz, ComplexPairFormat.IQ);
        }

        @Override public List<String> ports() { return List.of("in", "out0", "out1"); }

        @Override
        public Mixer withName(String newName) { return new Mixer(id, newName, loHz, format); }

        @Override
        public Mixer withId(ComponentId newId) { return new Mixer(newId, name, loHz, format); }

        @Override
        public void stamp(CircuitStampContext ctx) {
            ctx.stampMixer(ctx.port("in"), ctx.port("out0"), ctx.port("out1"), loHz, format);
        }

        public Mixer withLoHz(double v) { return new Mixer(id, name, v, format); }
        public Mixer withFormat(ComplexPairFormat v) { return new Mixer(id, name, loHz, v); }
    }

    // ─── Modulator (upconverter) ─────────────────────────────────────────────

    /**
     * Quadrature up-mixer. Reads two scalar node voltages from its
     * {@code in0} / {@code in1} ports — wired from any two
     * {@link VoltageSource} outputs — and emits the combined complex
     * phasor upconverted to {@code loHz} on a buffered {@code out}
     * node:
     *
     * <ul>
     *   <li>{@link ComplexPairFormat#IQ}: {@code V_out = (V_in0 + j·V_in1)}
     *       rotated by {@code exp(j·(2π·loHz − ω_sim)·t)}.</li>
     *   <li>{@link ComplexPairFormat#MAG_PHASE}: {@code V_out =
     *       V_in0 · exp(j·V_in1)} rotated by the same factor.</li>
     * </ul>
     *
     * <p>Symmetric mirror of {@link Mixer}: the mixer takes a complex
     * input and splits it into two scalars, the modulator takes two
     * scalars and combines them into a complex output.
     */
    record Modulator(
        ComponentId id,
        String name,
        double loHz,
        ComplexPairFormat format
    ) implements CircuitComponent {
        public Modulator {
            if (id == null) throw new IllegalArgumentException("Modulator.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Modulator.name must be non-blank");
            if (!Double.isFinite(loHz)) throw new IllegalArgumentException("Modulator.loHz must be finite");
            if (format == null) throw new IllegalArgumentException("Modulator.format must not be null");
        }

        /** Convenience constructor defaulting to {@link ComplexPairFormat#IQ}. */
        public Modulator(ComponentId id, String name, double loHz) {
            this(id, name, loHz, ComplexPairFormat.IQ);
        }

        @Override public List<String> ports() { return List.of("in0", "in1", "out"); }

        @Override
        public Modulator withName(String newName) { return new Modulator(id, newName, loHz, format); }

        @Override
        public Modulator withId(ComponentId newId) { return new Modulator(newId, name, loHz, format); }

        @Override
        public void stamp(CircuitStampContext ctx) {
            ctx.stampModulator(ctx.port("in0"), ctx.port("in1"), ctx.port("out"), loHz, format);
        }

        public Modulator withLoHz(double v) { return new Modulator(id, name, v, format); }
        public Modulator withFormat(ComplexPairFormat v) { return new Modulator(id, name, loHz, v); }

        /**
         * Walk the document's wires from {@code port} ("in0" or "in1") back
         * to the first {@link VoltageSource} whose {@code out} is on the
         * same electrical net. Returns null if there's no source at the
         * other end (or no wire).
         */
        public static VoltageSource inputSource(Modulator modulator, String port, CircuitDocument doc) {
            if (doc == null) return null;
            for (var terminal : CircuitGraph.netOf(doc, new ComponentTerminal(modulator.id(), port))) {
                if (!"out".equals(terminal.port())) continue;
                if (doc.component(terminal.componentId()).orElse(null) instanceof VoltageSource src) return src;
            }
            return null;
        }
    }

    // ─── Voltage metadata tap ────────────────────────────────────────────────

    /**
     * Derived-signal tap over a {@link VoltageSource}. The user picks the
     * observed source <em>by name</em> in the inspector — same pattern as
     * {@link ax.xz.mri.model.sequence.SequenceChannel#sourceName()} — and
     * the block emits a scalar on its single {@code out} port that
     * downstream switches, muxes, or probes can consume.
     *
     * <p>The only mode right now is {@link Mode#ACTIVE}: {@code out} reads
     * {@code 1} on any step where any of the referenced source's control
     * channels is non-zero ("a clip is playing"), and {@code 0} otherwise.
     * This replaces the old {@code active} port on {@link VoltageSource} —
     * one block per tap instead of every source carrying an extra port.
     *
     * <p>Electrically the block stamps an imposed-voltage branch on
     * {@code out} carrying the scalar value, so wiring the output into a
     * switch ctl, a probe, or any other consumer Just Works. The name
     * reference is a lookup, not a wire — keeps the schematic clean and
     * lets one metadata block float anywhere near its consumer.
     */
    record VoltageMetadata(
        ComponentId id,
        String name,
        String sourceName,
        Mode mode
    ) implements CircuitComponent {
        public enum Mode {
            /** 1.0 whenever any control channel on the source is non-zero; 0.0 otherwise. */
            ACTIVE
        }

        public VoltageMetadata {
            if (id == null) throw new IllegalArgumentException("VoltageMetadata.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("VoltageMetadata.name must be non-blank");
            if (mode == null) throw new IllegalArgumentException("VoltageMetadata.mode must not be null");
            // sourceName may be null or blank — that's valid (block hasn't been
            // pointed at a source yet); at compile time the tap resolves to zero.
        }

        /** Default-mode convenience constructor. */
        public VoltageMetadata(ComponentId id, String name, String sourceName) {
            this(id, name, sourceName, Mode.ACTIVE);
        }

        public VoltageMetadata(ComponentId id, String name) {
            this(id, name, null, Mode.ACTIVE);
        }

        @Override public List<String> ports() { return List.of("out"); }

        @Override
        public VoltageMetadata withName(String newName) { return new VoltageMetadata(id, newName, sourceName, mode); }

        @Override
        public VoltageMetadata withId(ComponentId newId) { return new VoltageMetadata(newId, name, sourceName, mode); }

        @Override
        public void stamp(CircuitStampContext ctx) {
            ctx.stampVoltageMetadata(sourceName, ctx.port("out"), mode);
        }

        public VoltageMetadata withMode(Mode newMode) { return new VoltageMetadata(id, name, sourceName, newMode); }
        public VoltageMetadata withSourceName(String newSourceName) { return new VoltageMetadata(id, name, newSourceName, mode); }
    }

    /** Ideal two-port transformer with ports {@code pa}, {@code pb} (primary) and {@code sa}, {@code sb} (secondary). */
    record IdealTransformer(ComponentId id, String name, double turnsRatio) implements CircuitComponent {
        public IdealTransformer {
            if (id == null) throw new IllegalArgumentException("IdealTransformer.id must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("IdealTransformer.name must be non-blank");
            if (!(Math.abs(turnsRatio) > 0) || !Double.isFinite(turnsRatio))
                throw new IllegalArgumentException("IdealTransformer.turnsRatio must be finite non-zero");
        }

        @Override public List<String> ports() { return List.of("pa", "pb", "sa", "sb"); }

        @Override
        public IdealTransformer withName(String newName) { return new IdealTransformer(id, newName, turnsRatio); }

        @Override
        public IdealTransformer withId(ComponentId newId) { return new IdealTransformer(newId, name, turnsRatio); }

        @Override
        public void stamp(CircuitStampContext ctx) {
            // No-op for now — true ideal-transformer stamping needs a
            // constrained-current branch; the component is placeable but
            // electrically inert until that arrives.
        }

        public IdealTransformer withTurnsRatio(double v) { return new IdealTransformer(id, name, v); }
    }
}
