package ax.xz.mri.ui.edit;

import ax.xz.mri.model.sequence.ClipKind;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.sequence.SignalClip;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resize is always asymmetric: dragging the left edge moves the start, the
 * end stays put; dragging the right edge moves the end, the start stays put.
 * The {@code stayCentred} flag is a hint for callers (the clip-skin gesture
 * handler) to call {@link EditSession#recentreClip} on release; at the
 * model layer this method just performs the asymmetric resize.
 */
class ClipEditorCentredResizeTest {
    private static final double TOL = 1e-6;

    @Test
    void resizeLeftKeepsRightEdge() {
        var session = new EditSession();
        var track = session.addTrack(SequenceChannel.of("a", 0), "A");
        var clip = SignalClip.freshCentred(track.id(), ClipKind.CONSTANT, 100.0, 200.0, 1.0);
        session.addClip(clip);

        session.resizeClipLeft(clip.id(), 150.0);
        var resized = session.findClip(clip.id());

        assertNotNull(resized);
        assertEquals(150.0, resized.startTime(), TOL);
        assertEquals(300.0, resized.endTime(), TOL);
        assertEquals(150.0, resized.duration(), TOL);
        assertTrue(resized.stayCentred());
    }

    @Test
    void resizeRightKeepsLeftEdge() {
        var session = new EditSession();
        var track = session.addTrack(SequenceChannel.of("a", 0), "A");
        var clip = SignalClip.freshCentred(track.id(), ClipKind.CONSTANT, 100.0, 200.0, 1.0);
        session.addClip(clip);

        session.resizeClipRight(clip.id(), 250.0);
        var resized = session.findClip(clip.id());

        assertNotNull(resized);
        assertEquals(100.0, resized.startTime(), TOL);
        assertEquals(250.0, resized.endTime(), TOL);
        assertEquals(150.0, resized.duration(), TOL);
        assertTrue(resized.stayCentred());
    }

    @Test
    void nonCentredResizeLeftKeepsRightEdge() {
        var session = new EditSession();
        var track = session.addTrack(SequenceChannel.of("a", 0), "A");
        var clip = SignalClip.freshCentred(track.id(), ClipKind.CONSTANT, 100.0, 200.0, 1.0)
            .withStayCentred(false);
        session.addClip(clip);

        session.resizeClipLeft(clip.id(), 150.0);
        var resized = session.findClip(clip.id());

        assertNotNull(resized);
        assertEquals(150.0, resized.startTime(), TOL);
        assertEquals(150.0, resized.duration(), TOL);
        assertEquals(300.0, resized.endTime(), TOL);
    }

    @Test
    void nonCentredResizeRightKeepsLeftEdge() {
        var session = new EditSession();
        var track = session.addTrack(SequenceChannel.of("a", 0), "A");
        var clip = SignalClip.freshCentred(track.id(), ClipKind.CONSTANT, 100.0, 200.0, 1.0)
            .withStayCentred(false);
        session.addClip(clip);

        session.resizeClipRight(clip.id(), 350.0);
        var resized = session.findClip(clip.id());

        assertNotNull(resized);
        assertEquals(100.0, resized.startTime(), TOL);
        assertEquals(250.0, resized.duration(), TOL);
    }

    @Test
    void resizeClampsAtDt() {
        var session = new EditSession();
        var track = session.addTrack(SequenceChannel.of("a", 0), "A");
        var clip = SignalClip.freshCentred(track.id(), ClipKind.CONSTANT, 100.0, 200.0, 1.0);
        session.addClip(clip);

        session.resizeClipLeft(clip.id(), 9999.0);
        var resized = session.findClip(clip.id());

        assertNotNull(resized);
        assertTrue(resized.duration() >= session.dt.get());
        assertTrue(resized.startTime() < resized.endTime());
    }

    @Test
    void freshCentredClipDefaultsStayCentredTrue() {
        var clip = SignalClip.freshCentred("track", ClipKind.CONSTANT, 0, 100, 1);
        assertTrue(clip.stayCentred());
    }

    @Test
    void recentreOnRequest() {
        var session = new EditSession();
        var track = session.addTrack(SequenceChannel.of("a", 0), "A");
        var clip = SignalClip.freshCentred(track.id(), ClipKind.CONSTANT, 100.0, 200.0, 1.0);
        session.addClip(clip);

        session.resizeClipRight(clip.id(), 350.0);
        session.recentreClip(clip.id());

        var resized = session.findClip(clip.id());
        assertNotNull(resized);
        assertEquals(250.0, resized.duration(), TOL);
        assertEquals(225.0, resized.centre(), TOL);
    }

    @Test
    void inspectorToggleFlipsRecentreIntent() {
        var session = new EditSession();
        var track = session.addTrack(SequenceChannel.of("a", 0), "A");
        var clip = SignalClip.freshCentred(track.id(), ClipKind.CONSTANT, 100.0, 200.0, 1.0);
        session.addClip(clip);

        session.replaceClip(clip.id(), clip.withStayCentred(false));

        session.resizeClipLeft(clip.id(), 150.0);
        var resized = session.findClip(clip.id());

        assertNotNull(resized);
        assertEquals(150.0, resized.startTime(), TOL);
        assertEquals(300.0, resized.endTime(), TOL);
        assertFalse(resized.stayCentred());
    }
}
