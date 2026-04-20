package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.simulation.AmplitudeKind;
import ax.xz.mri.model.simulation.SimulationConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One visual track lane in the sequence editor. Multiple tracks can share the
 * same {@link SequenceChannel}; each is independently collapsible.
 */
public record EditorTrack(String id, SequenceChannel channel, String label, boolean collapsed) {

    public EditorTrack withCollapsed(boolean collapsed) {
        return new EditorTrack(id, channel, label, collapsed);
    }

    public EditorTrack withLabel(String label) {
        return new EditorTrack(id, channel, label, collapsed);
    }

    /**
     * Default track set for a given simulation config — one track per
     * field-channel slot, plus a final RF-gate track.
     *
     * <p>If {@code config} is null, returns just the RF-gate track.
     */
    public static List<EditorTrack> defaultTracks(SimulationConfig config) {
        var tracks = new ArrayList<EditorTrack>();
        if (config != null) {
            for (var field : config.fields()) {
                int count = field.kind().channelCount();
                if (count == 0) continue; // STATIC contributes no editable channels
                for (int sub = 0; sub < count; sub++) {
                    String label = labelFor(field.name(), field.kind(), sub);
                    tracks.add(new EditorTrack(UUID.randomUUID().toString(),
                        SequenceChannel.ofField(field.name(), sub), label, false));
                }
            }
        }
        tracks.add(new EditorTrack(UUID.randomUUID().toString(),
            SequenceChannel.RF_GATE, "RF Gate", false));
        return tracks;
    }

    /** Compose a display label from a field's name, kind, and sub-index. */
    public static String labelFor(String fieldName, AmplitudeKind kind, int subIndex) {
        if (kind == AmplitudeKind.QUADRATURE) {
            return fieldName + " · " + (subIndex == 0 ? "I" : "Q");
        }
        return fieldName;
    }
}
