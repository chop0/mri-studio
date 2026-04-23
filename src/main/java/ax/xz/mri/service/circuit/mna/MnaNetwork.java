package ax.xz.mri.service.circuit.mna;

/**
 * Compile-time description of a circuit ready for per-step Modified Nodal
 * Analysis. All topology (nodes, which component sits where) is frozen; all
 * values that may change per step (switch state, capacitor history, source
 * voltages, coil EMF) are handled by {@link MnaSolver}.
 *
 * <p>Nodes are numbered {@code 0..nodeCount-1}. Ground is implicit and never
 * indexed; components that return to ground are stamped against index
 * {@code -1} and the solver drops those matrix rows/columns.
 *
 * <p>Each voltage branch carries an unknown current: one per source-{@code out},
 * one per source-{@code active}, one per coil or passive inductor. Branch
 * {@code b} occupies row {@code nodeCount + b} of the MNA system. Resistor,
 * capacitor, and switch stamps contribute only to the conductance block
 * (no branch row).
 *
 * <p>Data is held as parallel arrays rather than {@code List<Stamp>} records
 * so the hot path stays cache-friendly and the JIT can unroll the loops.
 */
public record MnaNetwork(
    int nodeCount,
    int voltageBranchCount,

    // Parallel resistor arrays: size = resistor count.
    int[] resistorA,
    int[] resistorB,
    double[] resistorConductance,

    // Parallel capacitor arrays: size = capacitor count.
    int[] capacitorA,
    int[] capacitorB,
    double[] capacitorFarads,

    // Parallel switch arrays: size = switch count (includes mux-expanded pairs).
    int[] switchA,
    int[] switchB,
    double[] switchClosedOhms,
    double[] switchOpenOhms,
    double[] switchThreshold,
    CtlBinding[] switchCtl,
    boolean[] switchInvert,

    // Voltage-branch arrays: size = voltageBranchCount.
    int[] branchNodeA,            // or -1 for ground
    int[] branchNodeB,
    VBranchKind[] branchKind,
    int[] branchRefIndex,         // source index for SOURCE_*, coil index for COIL, -1 for PASSIVE_INDUCTOR
    double[] branchR,             // series resistance (0 for pure source branches)
    double[] branchL,             // self-inductance (0 for pure source and resistor-only coil branches)

    // Index lookups.
    int[] sourceOutBranch,        // sourceOutBranch[s] = branch index of source s's "out" port
    int[] sourceActiveBranch,     // same for "active"
    int[] coilBranch,             // coilBranch[c] = branch index of coil c
    int[] probeNode               // probeNode[p] = node index for probe p's "in" (-1 if dangling)
) {

    public int systemSize() { return nodeCount + voltageBranchCount; }
    public int resistorCount() { return resistorA.length; }
    public int capacitorCount() { return capacitorA.length; }
    public int switchCount() { return switchA.length; }
    public int branchCount() { return branchKind.length; }
    public int coilCount() { return coilBranch.length; }
    public int probeCount() { return probeNode.length; }

    public enum VBranchKind {
        /** Imposed-voltage branch at a source's {@code out} port. */
        SOURCE_OUT,
        /** Imposed-voltage branch at a source's {@code active} port. */
        SOURCE_ACTIVE,
        /** Coil: R+L series branch with optional reciprocity EMF supplied each step. */
        COIL,
        /** Passive inductor (or shunt inductor): L-only branch, no EMF. */
        PASSIVE_INDUCTOR
    }

    /** How a switch's {@code ctl} port gets its value each step. */
    public sealed interface CtlBinding {
        /** ctl reads source {@code idx}'s {@code out} voltage. */
        record FromSourceOut(int sourceIndex) implements CtlBinding {}
        /** ctl reads source {@code idx}'s {@code active} flag. */
        record FromSourceActive(int sourceIndex) implements CtlBinding {}
        /** ctl is unbound; switch is always open. */
        record AlwaysOpen() implements CtlBinding {}
    }
}
