package ax.xz.mri.service.circuit.mna;

import ax.xz.mri.model.circuit.compile.ComplexPairFormat;
import ax.xz.mri.model.circuit.compile.CtlBinding;

/**
 * Compile-time description of a circuit ready for per-step Modified Nodal
 * Analysis. All topology (nodes, which component sits where) is frozen; all
 * values that may change per step (switch state, capacitor history, source
 * voltages, coil EMF, mixer output) are handled by {@link MnaSolver}.
 *
 * <p>Nodes are numbered {@code 0..nodeCount-1}. Ground is implicit and never
 * indexed; components that return to ground are stamped against index
 * {@code -1} and the solver drops those matrix rows/columns.
 *
 * <p>Each voltage branch carries an unknown current: one per source-{@code out},
 * one per source-{@code active}, one per coil, one per passive inductor, one
 * per mixer output. Branch {@code b} occupies row {@code nodeCount + b} of
 * the MNA system. Resistor, capacitor, and switch stamps contribute only to
 * the conductance block.
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
    int[] branchRefIndex,         // source idx for SOURCE_*, coil idx for COIL, mixer idx for MIXER_OUT, -1 for PASSIVE_INDUCTOR
    double[] branchR,             // series resistance (0 for pure voltage branches)
    double[] branchL,             // self-inductance (0 unless the branch integrates an L)

    // Index lookups.
    int[] sourceOutBranch,        // sourceOutBranch[s] = branch index of source s's "out" port
    int[] coilBranch,             // coilBranch[c] = branch index of coil c
    int[] probeNode,              // probeNode[p] = node index for probe p's "in" (-1 if dangling)

    // Mixer arrays: size = mixer count. Each mixer exposes two scalar
    // outputs (mixerOut0Branch / mixerOut1Branch) whose values come from
    // decomposing V(mixerInNode[m]) · exp(-j·2π·mixerLoHz[m]·t) per
    // mixerFormat[m] (IQ → real,imag; MAG_PHASE → |·|, arg).
    int[] mixerInNode,
    int[] mixerOut0Branch,
    int[] mixerOut1Branch,
    double[] mixerLoHz,
    ComplexPairFormat[] mixerFormat,

    // VoltageMetadata arrays: size = metadata count.
    //   metadataOutBranch[m] — the imposed-voltage branch carrying the
    //     0/1 scalar each step.
    //   metadataSourceIndices[m] — source-list indices the tap observes.
    //     A metadata block targeting a {@link ax.xz.mri.model.circuit.CircuitComponent.VoltageSource}
    //     resolves to a single-element array; one targeting a
    //     {@link ax.xz.mri.model.circuit.CircuitComponent.Modulator}
    //     resolves to that modulator's I and Q source indices so the tap
    //     fires when either envelope is playing.
    //   metadataMode[m] — how the indices combine per step (ACTIVE = OR).
    int[][] metadataSourceIndices,
    int[] metadataOutBranch,
    MetadataMode[] metadataMode,

    // Modulator arrays: size = modulator count. Each modulator reads two
    // scalar node voltages (modulatorIn0Node / modulatorIn1Node) and
    // stamps one output branch (modulatorOutBranch) combining them per
    // modulatorFormat[k]:
    //   IQ:         V_out = (V_in0 + j·V_in1) · exp(j·(2π·loHz - ω_sim)·t)
    //   MAG_PHASE:  V_out = V_in0 · exp(j·V_in1) · exp(j·(2π·loHz - ω_sim)·t)
    int[] modulatorIn0Node,
    int[] modulatorIn1Node,
    int[] modulatorOutBranch,
    double[] modulatorLoHz,
    ComplexPairFormat[] modulatorFormat
) {

    public enum MetadataMode { ACTIVE }

    public int systemSize() { return nodeCount + voltageBranchCount; }
    public int resistorCount() { return resistorA.length; }
    public int capacitorCount() { return capacitorA.length; }
    public int switchCount() { return switchA.length; }
    public int branchCount() { return branchKind.length; }
    public int coilCount() { return coilBranch.length; }
    public int probeCount() { return probeNode.length; }
    public int mixerCount() { return mixerInNode.length; }
    public int metadataCount() { return metadataSourceIndices.length; }
    public int modulatorCount() { return modulatorOutBranch.length; }

    /** True iff any component needs coupled-channel iteration (Mixer or Modulator). */
    public boolean needsComplexIteration() {
        return mixerCount() > 0 || modulatorCount() > 0;
    }

    public enum VBranchKind {
        /** Imposed-voltage branch at a source's {@code out} port. */
        SOURCE_OUT,
        /** Coil: R+L series branch with optional reciprocity EMF supplied each step. */
        COIL,
        /** Passive inductor (or shunt inductor): L-only branch, no EMF. */
        PASSIVE_INDUCTOR,
        /** Mixer first output — real-part (IQ) or magnitude (MAG_PHASE). */
        MIXER_OUT_0,
        /** Mixer second output — imag-part (IQ) or phase (MAG_PHASE). */
        MIXER_OUT_1,
        /** Voltage-metadata output: 0/1 "active" flag driven by the referenced source's controls. */
        METADATA_OUT,
        /** Modulator output: complex upconverted envelope of its two scalar inputs. */
        MODULATOR_OUT
    }
}
