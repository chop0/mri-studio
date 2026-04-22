package ax.xz.mri.model.sequence;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An output signal slot in a simulation config — identified by the
 * {@link ax.xz.mri.model.simulation.FieldDefinition}'s {@code name} plus an
 * in-field sub-index.
 *
 * <p>Sub-index semantics mirror {@link ax.xz.mri.model.simulation.AmplitudeKind}:
 * <ul>
 *   <li>{@code REAL} fields expose a single channel at {@code sub == 0}.</li>
 *   <li>{@code QUADRATURE} fields expose two channels:
 *       {@code sub == 0} is in-phase (I), {@code sub == 1} is quadrature (Q).</li>
 *   <li>{@code STATIC} fields expose no channels (their amplitude is fixed in
 *       the config).</li>
 * </ul>
 *
 * <p>Channels are always backed by a field in the config; there are no
 * sentinels or special cases. The RF-gate flag is computed at bake time from
 * the running magnitudes of the QUADRATURE fields — it is not a separate
 * channel users edit.
 */
public record SequenceChannel(
    @JsonProperty("field") String fieldName,
    @JsonProperty("sub") int subIndex
) {
    public SequenceChannel {
        if (fieldName == null || fieldName.isEmpty())
            throw new IllegalArgumentException("SequenceChannel.fieldName must be non-empty");
        if (subIndex < 0)
            throw new IllegalArgumentException("SequenceChannel.subIndex must be non-negative");
    }

    public static SequenceChannel ofField(String fieldName, int subIndex) {
        return new SequenceChannel(fieldName, subIndex);
    }
}
