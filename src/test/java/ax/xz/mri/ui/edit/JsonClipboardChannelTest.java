package ax.xz.mri.ui.edit;

import ax.xz.mri.model.sequence.ClipKind;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.support.FxTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link JsonClipboardChannel} round-trip and the format-isolation
 * guarantee — a paste-target only consumes Cmd+V when the clipboard
 * advertises its own {@code DataFormat}, so a circuit-component paste can't
 * silently corrupt a sequence editor.
 */
class JsonClipboardChannelTest {

    @Test
    void roundTripsClipsThroughTheSystemClipboard() {
        FxTestSupport.runOnFxThread(() -> {
            var channel = new JsonClipboardChannel<>("clips-rt", SignalClip.class);
            var clip = SignalClip.freshCentred("trk", ClipKind.CONSTANT, 100.0, 200.0, 0.5);
            assertTrue(channel.put(List.of(clip)));
            var got = channel.peek();
            assertEquals(1, got.size());
            assertEquals(clip.id(), got.get(0).id());
            assertEquals(clip.duration(), got.get(0).duration());
        });
    }

    @Test
    void formatsAreIsolated() {
        FxTestSupport.runOnFxThread(() -> {
            var clipsCh = new JsonClipboardChannel<>("clips-iso", SignalClip.class);
            var stringsCh = new JsonClipboardChannel<>("strings-iso", String.class);

            clipsCh.put(List.of(SignalClip.freshCentred("trk", ClipKind.CONSTANT, 0, 100, 1.0)));
            assertTrue(clipsCh.hasContent(),
                "clipsCh just put something — must report content");
            assertFalse(stringsCh.hasContent(),
                "stringsCh advertises a different format — must not see clip payload");
            assertEquals(0, stringsCh.peek().size());
        });
    }
}
