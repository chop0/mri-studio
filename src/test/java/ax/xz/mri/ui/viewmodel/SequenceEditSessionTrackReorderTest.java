package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.model.sequence.SequenceChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the contract for {@link SequenceEditSession#reorderTrack}. */
class SequenceEditSessionTrackReorderTest {

    @Test
    void movesTrackBetweenIndicesAndIsUndoable() {
        var session = new SequenceEditSession();
        var a = session.addTrack(SequenceChannel.of("a", 0), "A");
        var b = session.addTrack(SequenceChannel.of("b", 0), "B");
        var c = session.addTrack(SequenceChannel.of("c", 0), "C");

        // Move A to the end.
        session.reorderTrack(a.id(), 2);
        assertEquals(List.of(b.id(), c.id(), a.id()), trackIds(session));

        // Undo reverses.
        session.undo();
        assertEquals(List.of(a.id(), b.id(), c.id()), trackIds(session));
    }

    @Test
    void clampsOutOfRangeIndex() {
        var session = new SequenceEditSession();
        var a = session.addTrack(SequenceChannel.of("a", 0), "A");
        var b = session.addTrack(SequenceChannel.of("b", 0), "B");

        session.reorderTrack(a.id(), 99);
        assertEquals(List.of(b.id(), a.id()), trackIds(session));

        session.reorderTrack(a.id(), -5);
        assertEquals(List.of(a.id(), b.id()), trackIds(session));
    }

    @Test
    void noopOnSameIndex() {
        var session = new SequenceEditSession();
        var a = session.addTrack(SequenceChannel.of("a", 0), "A");
        var b = session.addTrack(SequenceChannel.of("b", 0), "B");

        int revBefore = session.revision.get();
        session.reorderTrack(a.id(), 0);
        // A no-op shouldn't push undo or bump revision.
        assertEquals(revBefore, session.revision.get());
        assertEquals(List.of(a.id(), b.id()), trackIds(session));
    }

    private static List<String> trackIds(SequenceEditSession s) {
        return s.tracks.stream().map(t -> t.id()).toList();
    }
}
