package ax.xz.mri.model.sequence;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An output signal slot in a simulation config — identified by the
 * {@link ax.xz.mri.model.simulation.DrivePath}'s {@code name} plus an
 * in-path sub-index.
 *
 * <p>Sub-index semantics mirror {@link ax.xz.mri.model.simulation.AmplitudeKind}:
 * <ul>
 *   <li>{@code REAL} and {@code GATE} paths expose a single channel at
 *       {@code sub == 0}.</li>
 *   <li>{@code QUADRATURE} paths expose two channels: {@code sub == 0} is
 *       in-phase (I), {@code sub == 1} is quadrature (Q).</li>
 *   <li>{@code STATIC} paths expose no channels (their amplitude is fixed).</li>
 * </ul>
 */
public record SequenceChannel(
    @JsonProperty("drive_path") String drivePathName,
    @JsonProperty("sub") int subIndex
) {
    public SequenceChannel {
        if (drivePathName == null || drivePathName.isEmpty())
            throw new IllegalArgumentException("SequenceChannel.drivePathName must be non-empty");
        if (subIndex < 0)
            throw new IllegalArgumentException("SequenceChannel.subIndex must be non-negative");
    }

    public static SequenceChannel ofPath(String drivePathName, int subIndex) {
        return new SequenceChannel(drivePathName, subIndex);
    }
}
