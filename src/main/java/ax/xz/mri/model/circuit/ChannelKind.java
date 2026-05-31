package ax.xz.mri.model.circuit;

/**
 * Signal kind carried by one port of a {@link CircuitComponent}.
 *
 * <p>Wires constrain matching kinds — an {@link #ELECTRICAL} port can only
 * connect to another {@code ELECTRICAL} port, {@link #OPTICAL} to
 * {@code OPTICAL}, {@link #CONTROL} to {@code CONTROL}. The MNA solver
 * walks only the electrical-kind subgraph; optical wires are read by the
 * simulator's substance-to-probe routing at probe-collect time; control
 * wires deliver per-step scalar values from sequence tracks into substance
 * kernel inputs.
 */
public enum ChannelKind {
    /** A node in the circuit graph carrying voltage / current (MNA solver). */
    ELECTRICAL,
    /** A photon-emission output port routed to an
     *  {@link ax.xz.mri.model.probe.OpticalCounter} probe. */
    OPTICAL,
    /** A per-step scalar from a sequence track (e.g. {@code laser_on}). */
    CONTROL
}
