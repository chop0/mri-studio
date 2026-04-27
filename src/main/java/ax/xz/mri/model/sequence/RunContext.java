package ax.xz.mri.model.sequence;

/**
 * Which execution context a sequence is being baked / evaluated for.
 *
 * <p>A {@link Track} carries two routings — one for simulation, one for
 * hardware — and the {@link ClipEvaluator} / {@link ClipBaker} pick the right
 * one based on the active {@code RunContext}. Clips on a track whose routing
 * for the active context is {@code null} contribute nothing in that context.
 */
public enum RunContext {
    SIMULATION,
    HARDWARE
}
