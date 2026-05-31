package ax.xz.mri.ui.timeline.element.clip;

import ax.xz.mri.model.sequence.ClipKind;
import ax.xz.mri.model.sequence.SequenceChannel;
import ax.xz.mri.model.sequence.SignalClip;
import ax.xz.mri.ui.edit.EditSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests pinning the per-frame cost of a clip-drag gesture.
 *
 * <p>Two wins are critical for 60-fps drag responsiveness on a real
 * arrangement:
 *
 * <ol>
 *   <li><b>{@link WaveformCache} survives startTime mutation.</b> A drag
 *       changes only {@code startTime}; the wave shape (samples in clip-local
 *       time) is identical. Pre-fix the cache was keyed on
 *       {@code clip.hashCode()} which is a record hash that flips on every
 *       mutation, so every drag frame re-ran ~320 {@code ClipEvaluator.evaluate}
 *       calls per visible clip — for a 100-clip arrangement, ~32 000
 *       evaluations per frame. After the fix the cache key drops
 *       {@code startTime}; 100 sequential {@code withStartTime} mutations
 *       produce one cache entry total per (clip, sampleCount).
 *   <li><b>{@link EditSession#mutate} skips the {@code beforeDoc} snapshot
 *       inside an active transaction.</b> Each frame currently allocates two
 *       {@code SequenceDocument} + {@code ClipSequence} + two
 *       {@code List.copyOf(...)} pairs and runs {@code Objects.equals} between
 *       them. That work is dead during a transaction (the transaction commits
 *       only the final state), so 100 in-transaction mutates should produce
 *       ≤ 1 {@code beforeDoc} construction.
 * </ol>
 */
class ClipDragPerfTest {

    @Test
    void waveformCacheSurvivesStartTimeMutation() {
        var cache = new WaveformCache();
        var clip = SignalClip.freshCentred("trk", ClipKind.CONSTANT, 100.0, 200.0, 1.0);
        int samples = 64;

        // Prime the cache once.
        cache.getOrCompute(clip, samples);
        int initialSize = cache.size();
        assertEquals(1, initialSize, "Initial sample compute should land one entry");

        // 100 startTime mutations — wave shape identical, so every getOrCompute
        // must HIT the same entry. No new keys, no new sample arrays.
        for (int i = 0; i < 100; i++) {
            var moved = clip.withStartTime(100.0 + i);
            cache.getOrCompute(moved, samples);
        }

        assertEquals(1, cache.size(),
            "After 100 startTime mutations the cache must hold ONE entry per "
            + "(clip identity, shape, duration, sampleCount). Pre-fix the cache "
            + "was keyed on clip.hashCode() which changes on every mutation, so "
            + "100 frames produced 100 entries and 100×samples evaluations. Got "
            + cache.size() + " entries.");
    }

    @Test
    void waveformCacheReusesArrayAcrossMutations() {
        var cache = new WaveformCache();
        var clip = SignalClip.freshCentred("trk", ClipKind.CONSTANT, 0.0, 50.0, 0.5);

        var first = cache.getOrCompute(clip, 32);
        var afterMove = cache.getOrCompute(clip.withStartTime(99.0), 32);

        assertEquals(System.identityHashCode(first), System.identityHashCode(afterMove),
            "Same clip identity + same shape ⇒ cache must return the same array. "
            + "If a fresh array is returned the cache is invalidating on every "
            + "frame and re-evaluating samples — the regression we're guarding against.");
    }

    @Test
    void mutateInsideTransactionSkipsRedundantSnapshotting() {
        var session = new EditSession();
        var track = session.addTrack(SequenceChannel.of("a", 0), "A");
        var clip = SignalClip.freshCentred(track.id(), ClipKind.CONSTANT, 100.0, 200.0, 1.0);
        session.addClip(clip);

        int beforeBaseline = session.currentDocCallCount();
        session.beginTransaction("drag");
        for (int i = 0; i < 100; i++) {
            session.moveClip(clip.id(), 100.0 + i);
        }
        session.endTransaction();
        int actual = session.currentDocCallCount() - beforeBaseline;

        // Pre-fix: each mutate() did beforeDoc + afterDoc = 2 currentDoc calls
        // per moveClip, so 100 moves = ~200+ calls. After the fix only afterDoc
        // is built inside a transaction (the per-frame beforeDoc/equals check
        // is dead work — the transaction commits only the final state anyway).
        // Allow some headroom for endTransaction + listener-side calls.
        assertTrue(actual <= 130,
            "100 in-transaction moveClip calls invoked currentDoc " + actual
            + " times — expected ≤ 130 (the post-fix budget is one afterDoc per "
            + "frame plus a small commit-time tail). Pre-fix was ~200+. mutate() "
            + "is still computing beforeDoc on every frame.");
    }
}
