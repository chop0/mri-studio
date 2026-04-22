package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.FieldDefinition;
import ax.xz.mri.model.simulation.SimulationConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * One visual lane in the sequence editor, derived from the active simulation
 * config. Tracks are pure view data — there is exactly one per
 * {@link SequenceChannel} slot that the simulator recognises, plus a terminal
 * {@link SequenceChannel#RF_GATE} row.
 *
 * <p>Tracks are never mutable state: whenever the config changes they are
 * rebuilt from scratch in the same order as
 * {@link SimulationConfig#fields()}. Users adjust visibility via per-channel
 * collapse state on {@link SequenceEditSession}.
 */
public record EditorTrack(SequenceChannel channel, String label, AmplitudeKind kind) {

    /** Is this the terminal RF-gate lane (not backed by a field)? */
    public boolean isGate() { return channel.isRfGate(); }

    /**
     * Build the canonical track list for a simulation config. One lane per
     * addressable field-channel slot, then the RF-gate lane.
     *
     * <p>If {@code config} is {@code null} we still emit the RF-gate lane so
     * the editor has somewhere to drop gate pulses — anything else depends on
     * the field layout.
     */
    public static List<EditorTrack> forConfig(SimulationConfig config) {
        var out = new ArrayList<EditorTrack>();
        if (config != null) {
            for (var field : config.fields()) {
                int count = field.kind().channelCount();
                for (int sub = 0; sub < count; sub++) {
                    out.add(new EditorTrack(
                        SequenceChannel.ofField(field.name(), sub),
                        labelFor(field, sub),
                        field.kind()
                    ));
                }
            }
        }
        out.add(new EditorTrack(SequenceChannel.RF_GATE, "RF Gate", null));
        return List.copyOf(out);
    }

    /** Compose a display label for a specific sub-channel of a field. */
    public static String labelFor(FieldDefinition field, int subIndex) {
        return labelFor(field.name(), field.kind(), subIndex);
    }

    /** Compose a display label from a field's name, kind and sub-index. */
    public static String labelFor(String fieldName, AmplitudeKind kind, int subIndex) {
        if (kind == AmplitudeKind.QUADRATURE) {
            return fieldName + " · " + (subIndex == 0 ? "I" : "Q");
        }
        return fieldName;
    }
}
