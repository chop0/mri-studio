package ax.xz.mri.model.circuit.compile;

import ax.xz.mri.model.circuit.CircuitComponent;
import ax.xz.mri.model.circuit.ComponentId;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.project.ProjectNodeId;

/**
 * SPI the compiler hands to each {@link ax.xz.mri.model.circuit.CircuitComponent}
 * while the component stamps itself into the MNA. The context is implicitly
 * bound to the component currently being stamped — {@link #port(String)}
 * resolves port names on that owner, not on the whole schematic.
 *
 * <p>The stamp methods split along two axes:
 * <ul>
 *   <li><b>Lumped passives / mixer</b>: {@link #stampResistor},
 *       {@link #stampCapacitor}, {@link #stampInductor},
 *       {@link #stampSwitch}, {@link #stampMixer}. These are anonymous
 *       from the simulator's perspective — they contribute only to MNA
 *       stamps.</li>
 *   <li><b>First-class simulator entities</b>: {@link #registerSource},
 *       {@link #registerCoil}, {@link #registerProbe}. These end up as typed
 *       entries in {@code CompiledCircuit} because the simulator consumes
 *       their metadata (channel offsets, eigenfield sample arrays, …).</li>
 * </ul>
 *
 * <p>The implementation is an internal detail of the compiler; records in
 * {@code model/circuit/} only depend on this interface, not on the MNA
 * builder arrays behind it.
 */
public interface CircuitStampContext {

    // ───── Node lookup ─────

    /** Resolves a port on the currently stamping component to its MNA node. */
    Node port(String portName);

    /** The implicit ground node. */
    Node ground();

    /**
     * Resolves a ctl port to the binding the solver consumes each step.
     * Usually called with {@code port("ctl")} from a switch / mux / gated
     * component.
     */
    CtlBinding resolveCtl(Node ctlPort);

    // ───── Lumped passives ─────

    void stampResistor(Node a, Node b, double ohms);

    void stampCapacitor(Node a, Node b, double farads);

    /** Passive (non-coil) inductor stamp — coils use {@link #registerCoil}. */
    void stampInductor(Node a, Node b, double henry);

    void stampSwitch(Node a, Node b, SwitchParams params);

    /**
     * Complex-baseband mixer: a buffered voltage source at {@code out}
     * whose value tracks {@code V(in) · exp(-j·2π·loHz·t)} in the
     * simulator's rotating frame. Ideal input impedance (zero input
     * current), ideal output impedance (zero Thevenin). The MNA resolves
     * the output each step by iterating until {@code V_in} stabilises —
     * one iteration suffices for DAG topologies.
     */
    void stampMixer(Node in, Node out, double loHz);

    // ───── First-class entities ─────

    /**
     * Register a voltage source. The context stamps one imposed-voltage
     * branch on {@code outPort} carrying the scheduled per-step amplitude
     * and appends a {@code CompiledSource} entry. Derived signals (e.g.
     * "is this source's clip playing") live on a downstream
     * {@link CircuitComponent.VoltageMetadata} block rather than an extra
     * port here.
     */
    void registerSource(ComponentId id, String name, AmplitudeKind kind,
                        double carrierHz, double staticAmplitude,
                        Node outPort);

    /**
     * Register a voltage-metadata tap. The context resolves which source
     * owns the node at {@code sourceInput} (by matching against each
     * source's registered {@code out} node) and stamps an imposed-voltage
     * branch on {@code outPort} whose per-step value follows
     * {@code mode} applied to that source's current controls.
     */
    void stampVoltageMetadata(Node sourceInput, Node outPort,
                              CircuitComponent.VoltageMetadata.Mode mode);

    /**
     * Register a coil. The context stamps one COIL branch between
     * {@code inPort} and ground carrying the user's series R / self-L,
     * samples the eigenfield onto the compile-time grid, and appends a
     * {@code CompiledCoil} entry.
     */
    void registerCoil(ComponentId id, String name, ProjectNodeId eigenfieldId,
                      double selfInductanceHenry, double seriesResistanceOhms,
                      Node inPort);

    /**
     * Register a probe. The context appends a {@code CompiledProbe} entry;
     * probes don't load the circuit, so no MNA stamps are emitted.
     */
    void registerProbe(ComponentId id, String name, double gain,
                       double demodPhaseDeg, double loadImpedanceOhms,
                       Node inPort);
}
