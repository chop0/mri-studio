package ax.xz.mri.optimisation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LbfgsbSolverTest {
    @Test
    void convergesToProjectedOptimumWithinBounds() {
        var solver = new LbfgsbSolver();

        var result = solver.solve(
            x -> new LbfgsbSolver.ValueGradient(
                (x[0] - 2.0) * (x[0] - 2.0),
                new double[]{2.0 * (x[0] - 2.0)}
            ),
            new double[]{0.0},
            new double[]{-5.0},
            new double[]{1.0},
            40,
            null
        );

        assertTrue(result.success());
        assertEquals(1.0, result.x()[0], 1e-6);
        assertEquals(1.0, result.value(), 1e-6);
    }

    @Test
    void listenerCanStopAStageEarly() {
        var solver = new LbfgsbSolver();

        var result = solver.solve(
            x -> new LbfgsbSolver.ValueGradient(
                (x[0] - 1.0) * (x[0] - 1.0),
                new double[]{2.0 * (x[0] - 1.0)}
            ),
            new double[]{5.0},
            new double[]{-10.0},
            new double[]{10.0},
            40,
            (iteration, x, value) -> iteration >= 1
        );

        assertFalse(result.success());
        assertEquals("Stopped by listener", result.message());
        assertTrue(result.iterations() >= 1);
        assertTrue(result.value() < 16.0);
    }
}
