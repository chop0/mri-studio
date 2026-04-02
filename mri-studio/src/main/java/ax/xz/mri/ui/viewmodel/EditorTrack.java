package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.sequence.SignalChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One visual track lane in the sequence editor. Multiple tracks can share
 * the same {@link SignalChannel}; each is independently collapsible.
 */
public record EditorTrack(String id, SignalChannel channel, String label, boolean collapsed) {

    public EditorTrack withCollapsed(boolean collapsed) {
        return new EditorTrack(id, channel, label, collapsed);
    }

    public EditorTrack withLabel(String label) {
        return new EditorTrack(id, channel, label, collapsed);
    }

    /** Default tracks: one expanded track per signal channel. */
    public static List<EditorTrack> defaultTracks() {
        var tracks = new ArrayList<EditorTrack>();
        for (var ch : SignalChannel.values()) {
            tracks.add(new EditorTrack(UUID.randomUUID().toString(), ch, ch.label(), false));
        }
        return tracks;
    }
}
