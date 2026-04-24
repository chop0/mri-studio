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

    /** ctl reads source {@code idx}'s {@code active} flag (1 when any control channel is non-zero). */
    record FromSourceActive(int sourceIndex) implements CtlBinding {}

    /** ctl is unbound (dangling, or driven by an unsupported topology); switch is permanently open. */
    record AlwaysOpen() implements CtlBinding {}
}
