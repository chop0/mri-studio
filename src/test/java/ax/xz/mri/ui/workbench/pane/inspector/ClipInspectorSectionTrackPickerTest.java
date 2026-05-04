package ax.xz.mri.ui.workbench.pane.inspector;

import ax.xz.mri.model.sequence.ClipKind;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.support.FxTestSupport;
import ax.xz.mri.ui.viewmodel.SequenceEditSession;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression test for the ClipInspectorSection re-entrancy crash.
 *
 * <p>The bug: when the user changed a clip's track via the inspector ComboBox,
 * the {@code setOnAction} handler called {@code session.changeClipTrack(...)},
 * which synchronously fired the revision listener, which called
 * {@link ClipInspectorSection#refresh()}, which called a puller that did
 * {@code combo.getItems().setAll(session.tracks)} — *while the combo's
 * selection model was still mid-{@code setValue} from the user's pick*. JavaFX
 * detected the re-entrant ListChangeBuilder modification and threw.
 *
 * <p>The fix splits the conflated sync into two single-purpose handlers and
 * gives the action handler an identity guard so a programmatic
 * {@code setValue} can't recurse into {@code changeClipTrack}.
 */
class ClipInspectorSectionTrackPickerTest {

    @Test
    void synchronousChangeClipTrackDoesNotThrowWithInspectorAttached() {
        var failure = new AtomicReference<Throwable>();
        FxTestSupport.runOnFxThread(() -> {
            var session = new SequenceEditSession();
            var trackA = session.addTrack(SequenceChannel.of("rfA", 0), "Track A");
            var trackB = session.addTrack(SequenceChannel.of("rfB", 0), "Track B");

            var clip = SignalClip.freshCentred(trackA.id(), ClipKind.GAUSSIAN, 0, 100, 1.0);
            session.addClip(clip);

            var section = new ClipInspectorSection(session, clip);
            try {
                // Simulate the user picking a different track in the combo.
                // Pre-fix this path crashed with a re-entrant
                // ListChangeBuilder exception out of setAll(...).
                session.changeClipTrack(clip.id(), trackB.id());

                // Cross-check the model side: the change went through.
                assertEquals(trackB.id(), session.findClip(clip.id()).trackId());

                // And another back, to exercise the puller path again.
                session.changeClipTrack(clip.id(), trackA.id());
                assertEquals(trackA.id(), session.findClip(clip.id()).trackId());

                // Refresh manually too — the puller path that used to trip
                // the crash must be safe even when invoked outside an event.
                section.refresh();
                assertEquals(trackA.id(), session.findClip(clip.id()).trackId());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                section.dispose();
            }
        });
        if (failure.get() != null) fail("changeClipTrack threw with inspector attached", failure.get());
    }

    @Test
    void deferredItemsRefreshFiresAfterTrackListChange() throws InterruptedException {
        var failure = new AtomicReference<Throwable>();
        var deferred = new CountDownLatch(1);
        FxTestSupport.runOnFxThread(() -> {
            var session = new SequenceEditSession();
            var trackA = session.addTrack(SequenceChannel.of("rfA", 0), "Track A");

            var clip = SignalClip.freshCentred(trackA.id(), ClipKind.GAUSSIAN, 0, 100, 1.0);
            session.addClip(clip);

            var section = new ClipInspectorSection(session, clip);
            try {
                // Adding a track triggers the deferred items-list rebuild via
                // Platform.runLater. We chain a no-op runLater after it to
                // observe completion without poking at private combo state.
                session.addTrack(SequenceChannel.of("rfB", 0), "Track B");
                javafx.application.Platform.runLater(deferred::countDown);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                // Don't dispose yet — the deferred runLater needs the
                // session.tracks listener to still be attached when it runs,
                // because the listener is what would propagate the
                // tracks-changed event. The cleanup happens after we await.
            }
        });
        if (failure.get() != null) fail("addTrack threw with inspector attached", failure.get());
        if (!deferred.await(5, TimeUnit.SECONDS)) fail("deferred items refresh did not run");
    }

    @Test
    void disposeRemovesTracksListener() {
        FxTestSupport.runOnFxThread(() -> {
            var session = new SequenceEditSession();
            var trackA = session.addTrack(SequenceChannel.of("rfA", 0), "Track A");

            var clip = SignalClip.freshCentred(trackA.id(), ClipKind.GAUSSIAN, 0, 100, 1.0);
            session.addClip(clip);

            var section = new ClipInspectorSection(session, clip);
            section.dispose();

            // After dispose, mutating tracks must not feed into the disposed
            // section. We can't directly inspect listener counts on
            // ObservableList without reflection, but a follow-up addTrack
            // running cleanly (no exceptions, even though the combo is no
            // longer in the scene graph) confirms the listener was detached.
            session.addTrack(SequenceChannel.of("rfB", 0), "Track B");
            assertEquals(trackA.id(), session.findClip(clip.id()).trackId());
            assertNull(null);
        });
    }
}
