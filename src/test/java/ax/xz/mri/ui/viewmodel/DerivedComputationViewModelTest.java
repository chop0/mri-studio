package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.service.simulation.SignalTraceComputer;
import ax.xz.mri.support.TestSimulationOutputFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DerivedComputationViewModelTest {
    @Test
    void staleGenerationDoesNotOverwriteNewerDerivedResults() {
        var executor = new ManualExecutor();
        var derived = new DerivedComputationViewModel(executor, Runnable::run, () -> { });
        var data = TestSimulationOutputFactory.sampleDocument();
        var pulseA = TestSimulationOutputFactory.pulseA();
        var pulseB = TestSimulationOutputFactory.pulseB();

        var expectedA = SignalTraceComputer.compute(data, pulseA);
        var expectedB = SignalTraceComputer.compute(data, pulseB);
        assertNotEquals(expectedA.points(), expectedB.points());

        derived.recompute(data, pulseA);
        derived.recompute(data, pulseB);

        assertTrue(derived.computing.get());
        executor.runNext();
        assertNull(derived.signalTrace.get());

        executor.runNext();
        assertEquals(expectedB.points(), derived.signalTrace.get().points());
        assertNotNull(derived.phaseMapZ.get());
        assertNotNull(derived.phaseMapR.get());
        assertFalse(derived.computing.get());
        assertNull(derived.errorMessage.get());
    }

    @Test
    void failuresClearStaleOutputsAndSurfaceAnError() {
        var derived = new DerivedComputationViewModel((Executor) Runnable::run, Runnable::run, () -> { });
        var data = TestSimulationOutputFactory.sampleDocument();

        derived.recompute(data, TestSimulationOutputFactory.pulseA());
        assertNotNull(derived.signalTrace.get());

        derived.recompute(TestSimulationOutputFactory.brokenDocumentMissingSegments(), TestSimulationOutputFactory.pulseA());

        assertNull(derived.phaseMapZ.get());
        assertNull(derived.phaseMapR.get());
        assertNull(derived.signalTrace.get());
        assertNotNull(derived.errorMessage.get());
        assertFalse(derived.computing.get());
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            var next = tasks.remove();
            next.run();
        }
    }
}
