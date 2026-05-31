package ax.xz.mri.ui.sim;

import ax.xz.mri.support.FxTestSupport;
import ax.xz.mri.ui.time.Generation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the dispatcher's coalescing/cancellation logic without spinning up
 * the simulation pipeline. A {@link RecordingSubmitter} stands in for
 * {@link SimRunner}; the test verifies that bursts of {@link SimDispatcher#markDirty()}
 * collapse into a single submission and that stale results are dropped via the
 * shared {@link Generation} guard.
 */
class SimDispatcherCoalescingTest {
    private static final long DEBOUNCE_TIMEOUT_MS = 2000;

    @Test
    void rapidMarkDirtyCallsCoalesceIntoOneSubmission() throws Exception {
        FxTestSupport.startToolkit();
        var generation = new Generation();
        var submitter = new RecordingSubmitter();
        var capturedRequest = new AtomicReference<SimRequest>();
        var supplier = (java.util.function.Supplier<SimRequest>) () -> {
            var req = new SimRequest("test", null, null, null, generation.current());
            capturedRequest.set(req);
            return req;
        };
        var dispatcher = new SimDispatcher(supplier, submitter, generation,
            r -> {}, (m, t) -> {});

        FxTestSupport.runOnFxThread(() -> {
            dispatcher.markDirty();
            dispatcher.markDirty();
            dispatcher.markDirty();
            dispatcher.markDirty();
        });

        assertTrue(submitter.awaitSubmission(DEBOUNCE_TIMEOUT_MS),
            "Expected one submission within debounce window");
        assertEquals(1, submitter.submissions.size(),
            "Multiple markDirty calls should coalesce into a single submission");
        assertNotNull(capturedRequest.get());
    }

    @Test
    void simulateBypassesDebounceImmediately() throws Exception {
        FxTestSupport.startToolkit();
        var generation = new Generation();
        var submitter = new RecordingSubmitter();
        var supplier = (java.util.function.Supplier<SimRequest>) () ->
            new SimRequest("test", null, null, null, generation.current());
        var dispatcher = new SimDispatcher(supplier, submitter, generation,
            r -> {}, (m, t) -> {});

        FxTestSupport.runOnFxThread(dispatcher::simulate);

        // simulate() runs the submission synchronously on the FX thread.
        // No debounce wait needed.
        assertEquals(1, submitter.submissions.size());
    }

    @Test
    void nullRequestSkipsSubmissionAndReturnsToIdle() throws Exception {
        FxTestSupport.startToolkit();
        var generation = new Generation();
        var submitter = new RecordingSubmitter();
        var dispatcher = new SimDispatcher(() -> null, submitter, generation,
            r -> {}, (m, t) -> {});

        FxTestSupport.runOnFxThread(dispatcher::simulate);

        assertEquals(0, submitter.submissions.size());
        assertEquals(SimState.IDLE, dispatcher.state.get());
    }

    @Test
    void autoSimulateOffSuppressesDebouncedSubmissions() throws Exception {
        FxTestSupport.startToolkit();
        var generation = new Generation();
        var submitter = new RecordingSubmitter();
        var supplier = (java.util.function.Supplier<SimRequest>) () ->
            new SimRequest("test", null, null, null, generation.current());
        var dispatcher = new SimDispatcher(supplier, submitter, generation,
            r -> {}, (m, t) -> {});

        FxTestSupport.runOnFxThread(() -> {
            dispatcher.autoSimulate.set(false);
            dispatcher.markDirty();
            dispatcher.markDirty();
        });

        Thread.sleep(400);
        assertEquals(0, submitter.submissions.size(),
            "Debounce should not fire when auto-simulate is off");
        assertEquals(SimState.PENDING, dispatcher.state.get(),
            "State stays pending until simulate() is invoked manually");
    }

    @Test
    void disposeCancelsPendingDebounceAndDispatchesNoMore() throws Exception {
        FxTestSupport.startToolkit();
        var generation = new Generation();
        var submitter = new RecordingSubmitter();
        var supplier = (java.util.function.Supplier<SimRequest>) () ->
            new SimRequest("test", null, null, null, generation.current());
        var dispatcher = new SimDispatcher(supplier, submitter, generation,
            r -> {}, (m, t) -> {});

        FxTestSupport.runOnFxThread(() -> {
            dispatcher.markDirty();
            dispatcher.dispose();
        });

        Thread.sleep(400);
        assertEquals(0, submitter.submissions.size());
        assertTrue(submitter.disposed);
    }

    @Test
    void resultPublishesAndUpdatesObservableState() throws Exception {
        FxTestSupport.startToolkit();
        var generation = new Generation();
        var submitter = new RecordingSubmitter();
        var supplier = (java.util.function.Supplier<SimRequest>) () ->
            new SimRequest("test", null, null, null, generation.current());
        List<SimResult> published = new ArrayList<>();
        var dispatcher = new SimDispatcher(supplier, submitter, generation,
            published::add, (m, t) -> {});

        FxTestSupport.runOnFxThread(dispatcher::simulate);
        assertEquals(1, submitter.submissions.size());

        var fakeResult = new SimResult(null, List.of(), null, generation.current());
        FxTestSupport.runOnFxThread(() -> submitter.fireResult(fakeResult));

        assertEquals(1, published.size());
        assertSame(fakeResult, published.get(0));
        assertSame(fakeResult, dispatcher.result.get());
        assertEquals(SimState.IDLE, dispatcher.state.get());
    }

    @Test
    void errorReportsToErrorReporterAndSetsFailedState() throws Exception {
        FxTestSupport.startToolkit();
        var generation = new Generation();
        var submitter = new RecordingSubmitter();
        var supplier = (java.util.function.Supplier<SimRequest>) () ->
            new SimRequest("test", null, null, null, generation.current());
        var capturedMessage = new AtomicReference<String>();
        var dispatcher = new SimDispatcher(supplier, submitter, generation,
            r -> {}, (m, t) -> capturedMessage.set(m));

        FxTestSupport.runOnFxThread(dispatcher::simulate);
        var failure = new IllegalStateException("kaboom");
        FxTestSupport.runOnFxThread(() -> submitter.fireError(failure));

        assertEquals("kaboom", capturedMessage.get());
        assertTrue(dispatcher.state.get() instanceof SimState.Failed,
            "Expected Failed state, got " + dispatcher.state.get());
        assertEquals("kaboom", ((SimState.Failed) dispatcher.state.get()).message());
    }

    /** Captures submissions and lets tests fire result/error callbacks at will. */
    private static final class RecordingSubmitter implements SimSubmitter {
        final List<SimRequest> submissions = new ArrayList<>();
        boolean disposed;

        private Consumer<SimResult> lastOnResult;
        private Consumer<Throwable> lastOnError;
        private CountDownLatch pendingSubmission = new CountDownLatch(1);

        @Override
        public void submit(SimRequest request, Consumer<SimResult> onResult, Consumer<Throwable> onError) {
            submissions.add(request);
            lastOnResult = onResult;
            lastOnError = onError;
            pendingSubmission.countDown();
        }

        boolean awaitSubmission(long timeoutMs) throws InterruptedException {
            return pendingSubmission.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        void fireResult(SimResult result) {
            assertNotNull(lastOnResult, "submit must be called before fireResult");
            lastOnResult.accept(result);
        }

        void fireError(Throwable failure) {
            assertNotNull(lastOnError, "submit must be called before fireError");
            lastOnError.accept(failure);
        }

        @Override
        public void dispose() {
            disposed = true;
        }
    }
}
