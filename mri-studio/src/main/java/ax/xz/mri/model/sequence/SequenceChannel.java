package ax.xz.mri.model.sequence;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A channel in a clip sequence — either a field-channel slot (identified by
 * the {@link ax.xz.mri.model.simulation.FieldDefinition}'s {@code name} plus
 * an in-field sub-index), or the special RF-gate sentinel that carries the
 * on/off hint consumed by the simulator's fast-path selector.
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
 * <p>{@link #RF_GATE} is a reserved sentinel with empty {@code fieldName} and
 * {@code sub == -1}. It is the only channel not backed by a FieldDefinition.
 */
public record SequenceChannel(
    @JsonProperty("field") String fieldName,
    @JsonProperty("sub") int subIndex
) {
    /** Reserved RF on/off gate, not associated with any FieldDefinition. */
    public static final SequenceChannel RF_GATE = new SequenceChannel("", -1);

    public SequenceChannel {
        if (fieldName == null) fieldName = "";
    }

    public static SequenceChannel ofField(String fieldName, int subIndex) {
        if (fieldName == null || fieldName.isEmpty())
            throw new IllegalArgumentException("Field channel requires a non-empty field name");
        if (subIndex < 0)
            throw new IllegalArgumentException("Sub-index must be non-negative for a field channel");
        return new SequenceChannel(fieldName, subIndex);
    }

    public boolean isRfGate() { return fieldName.isEmpty() && subIndex < 0; }
}
