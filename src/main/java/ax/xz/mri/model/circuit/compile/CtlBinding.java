package ax.xz.mri.model.circuit.compile;

/**
 * How a switch's {@code ctl} port gets its value each step.
 *
 * <p>Resolved at compile time by {@link CircuitStampContext#resolveCtl} so
 * the hot path never re-walks the schematic. Variants intentionally only
 * cover the cases our rotating-frame MNA can handle without a nonlinear
 * fixed-point solve — ctl driven by an arbitrary network node voltage would
 * require one, so that case is folded into {@link AlwaysOpen} for now.
 */
public sealed interface CtlBinding {
    /** ctl reads source {@code idx}'s {@code out} voltage (treated as a gate level). */
    record FromSourceOut(int sourceIndex) implements CtlBinding {}

    /**
     * ctl reads the <em>OR</em> of the referenced sources' "active" flags —
     * 1 when any named source has a non-zero control channel this step.
     * Single-source metadata taps produce a single-element array; taps
     * targeting a {@link ax.xz.mri.model.circuit.CircuitComponent.Modulator}
     * produce both its I and Q source indices.
     */
    record FromSourceActive(int[] sourceIndices) implements CtlBinding {
        public FromSourceActive {
            sourceIndices = sourceIndices == null ? new int[0] : sourceIndices.clone();
        }
    }

    /** ctl is unbound (dangling, or driven by an unsupported topology); switch is permanently open. */
    record AlwaysOpen() implements CtlBinding {}
}
