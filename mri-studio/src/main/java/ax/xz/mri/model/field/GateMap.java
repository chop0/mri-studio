package ax.xz.mri.model.field;

/**
 * Runtime lookup for a named gate signal emitted by a
 * {@link ax.xz.mri.model.simulation.DrivePath} with
 * {@link ax.xz.mri.model.simulation.AmplitudeKind#GATE}.
 *
 * <p>A gate produces no B-field; it only carries a scalar that other paths
 * and receive coils can read at each simulation step. {@link #channelOffset}
 * indexes the gate's control scalar inside a {@code PulseStep.controls} array.
 */
public record GateMap(String name, int channelOffset) {}
