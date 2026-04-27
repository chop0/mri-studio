package ax.xz.mri.service.simulation;

import ax.xz.mri.model.simulation.MagnetisationState;
import ax.xz.mri.support.TestSimulationOutputFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BlochSimulatorTest {
    @Test
    void simulateCachesFullTrajectoriesForRepeatedRequests() {
        BlochSimulator.clearCachesForTests();
        var data = TestSimulationOutputFactory.sampleDocument();
        var pulse = TestSimulationOutputFactory.pulseA();

        var first = BlochSimulator.simulate(data, 0.0, 2.0, pulse);
        var second = BlochSimulator.simulate(data, 0.0, 2.0, pulse);

        assertSame(first, second);
    }

    @Test
    void simulateToMatchesDiscreteTrajectorySamplingAcrossMixedCursorOrders() {
        BlochSimulator.clearCachesForTests();
        var data = TestSimulationOutputFactory.sampleDocument();
        var pulse = TestSimulationOutputFactory.pulseA();
        var trajectory = BlochSimulator.simulate(data, 15.0, -3.0, pulse);
        var sampleTimes = List.of(0.0, 0.4, 1.0, 1.7, 2.0, 0.9, 10.0);

        BlochSimulator.clearCachesForTests();
        for (double tMicros : sampleTimes) {
            assertStateEquals(trajectory.stepStateAt(tMicros), BlochSimulator.simulateTo(data, 15.0, -3.0, pulse, tMicros));
        }
    }

    private static void assertStateEquals(MagnetisationState expected, MagnetisationState actual) {
        assertEquals(expected.mx(), actual.mx(), 1e-5);
        assertEquals(expected.my(), actual.my(), 1e-5);
        assertEquals(expected.mz(), actual.mz(), 1e-5);
    }
}
