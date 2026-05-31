package ax.xz.mri.dsl;

/**
 * Named handle for a voltage source in the active simulation's compiled
 * circuit. Mirror of {@link ProbeKey} on the drive side: scripts resolve
 * the sources they need once at the top of {@code run(ctx)}, then pass
 * the handles around to every {@link SequenceBuilder} call instead of
 * threading bare strings.
 *
 * <p>The {@code channelOffset} is baked in at resolution time — for the
 * lifetime of a {@link ScriptContext} the simulation's compiled circuit
 * doesn't change, so the offset is stable. {@link SequenceBuilder}
 * writes directly into {@code controls[channelOffset]} with no per-call
 * map lookup.
 *
 * <p>Resolve via {@link ScriptContext#source(String)} (validates against
 * the active simulation's source list and throws on typo).
 */
public record SourceKey(String name, int channelOffset) {

    public SourceKey {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("SourceKey name must not be blank");
        if (channelOffset < 0)
            throw new IllegalArgumentException(
                "SourceKey channelOffset must be ≥ 0, got " + channelOffset
                + " (for source '" + name + "')");
    }

    @Override public String toString() {
        return "SourceKey(" + name + " @ channel " + channelOffset + ")";
    }
}
