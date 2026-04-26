package ax.xz.mri.model.sequence;

/**
 * An output signal slot identified by a named voltage source in the active
 * circuit plus an in-source sub-index.
 *
 * <p>Sub-index semantics mirror
 * {@link ax.xz.mri.model.simulation.AmplitudeKind}:
 * <ul>
 *   <li>{@code REAL} and {@code GATE} sources expose one channel
 *       at {@code sub == 0}.</li>
 *   <li>{@code QUADRATURE} sources expose two: {@code sub == 0} is
 *       in-phase (I), {@code sub == 1} is quadrature (Q).</li>
 *   <li>{@code STATIC} sources expose no channels.</li>
 * </ul>
 */
public record SequenceChannel(
    String sourceName,
    int subIndex
) {
    public SequenceChannel {
        if (sourceName == null || sourceName.isEmpty())
            throw new IllegalArgumentException("SequenceChannel.sourceName must be non-empty");
        if (subIndex < 0)
            throw new IllegalArgumentException("SequenceChannel.subIndex must be non-negative");
    }

    public static SequenceChannel of(String sourceName, int subIndex) {
        return new SequenceChannel(sourceName, subIndex);
    }
}
