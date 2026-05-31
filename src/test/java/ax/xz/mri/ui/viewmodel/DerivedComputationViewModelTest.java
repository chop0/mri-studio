package ax.xz.mri.ui.viewmodel;

import ax.xz.mri.support.TestSimulationFactory;
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
        var simulationA = TestSimulationFactory.sampleSimulation();
        var simulationB = TestSimulationFactory.sampleSimulationWithPulseB();
        var pulseA = TestSimulationFactory.pulseA();
        var pulseB = TestSimulationFactory.pulseB();

        var expectedA = simulationA.runMultiProbe().primary();
        var expectedB = simulationB.runMultiProbe().primary();
        assertNotEquals(expectedA.points(), expectedB.points());

        derived.recompute(simulationA, pulseA, null);
        derived.recompute(simulationB, pulseB, null);

        assertTrue(derived.computing.get());
        executor.runNext();
        assertNull(derived.signalTrace.get());

        executor.runNext();
        assertEquals(expectedB.points(), derived.signalTrace.get().points());
        assertFalse(derived.computing.get());
        assertNull(derived.errorMessage.get());
    }

    @Test
    void resettingToNullClearsStaleOutputs() {
        var derived = new DerivedComputationViewModel((Executor) Runnable::run, Runnable::run, () -> { });
        var simulation = TestSimulationFactory.sampleSimulation();

        derived.recompute(simulation, TestSimulationFactory.pulseA(), null);
        assertNotNull(derived.signalTrace.get());

        derived.recompute(null, null, null);

        assertNull(derived.signalTrace.get());
        assertNull(derived.errorMessage.get());
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
