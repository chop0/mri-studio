package ax.xz.mri.optimisation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptimisationModelTest {
    @Test
    void controlSegmentSpecReportsDerivedLengths() {
        var spec = new ControlSegmentSpec(2.5e-6, 3, 5, 5);

        assertEquals(8, spec.totalSteps());
        assertEquals(40, spec.flattenedLength());
        assertEquals(20.0, spec.durationMicros(), 1e-9);
    }

    @Test
    void sequenceTemplateReportsPeriodicExpansionMetadata() {
        var template = SequenceTemplate.periodicCycle(
            List.of(
                new ControlSegmentSpec(1e-6, 2, 0, 5),
                new ControlSegmentSpec(1e-6, 1, 1, 5)
            ),
            1,
            1,
            4
        );

        assertTrue(template.periodic());
        assertEquals(2, template.reducedSegmentCount());
        assertEquals(5, template.expandedSegmentCount());
        assertEquals(20, template.flattenedLength());
    }

    @Test
    void continuationScheduleDefaultSplitsIterationsAcrossThreeStages() {
        var schedule = ContinuationSchedule.defaultForIterations(20);

        assertEquals(3, schedule.stages().size());
        assertEquals(20, schedule.stages().stream().mapToInt(ContinuationStage::iterations).sum());
        assertEquals(List.of(10.0, 3.0, 1.0), schedule.stages().stream().map(ContinuationStage::rfSmoothMultiplier).toList());
    }

    @Test
    void snapshotSeriesKeepsIterationsSorted() {
        var pulse = OptimisationTestSupport.pulseA();
        var snapshots = new SnapshotSeries(new LinkedHashMap<>() {{
            put(5, pulse);
            put(0, pulse);
            put(2, pulse);
        }});

        assertIterableEquals(List.of(0, 2, 5), new ArrayList<>(snapshots.snapshots().keySet()));
    }

    @Test
    void continuationOptimiserTracksBestVectorAndSnapshots() {
        var optimiser = new ContinuationOptimiser();
        var schedule = new ContinuationSchedule(List.of(
            new ContinuationStage(2, 10.0),
            new ContinuationStage(1, 1.0)
        ));

        var result = optimiser.run(schedule, new double[]{3.0}, 1, (stage, x0, listener) -> {
            double[] current = x0.clone();
            double value = current[0] * current[0];
            for (int iteration = 1; iteration <= stage.iterations(); iteration++) {
                current[0] -= 1.0;
                value = current[0] * current[0];
                listener.onIteration(iteration, current.clone(), value);
            }
            return new LbfgsbSolver.SolverResult(current.clone(), value, stage.iterations(), true, "ok");
        });

        assertArrayEquals(new double[]{0.0}, result.bestX(), 1e-9);
        assertEquals(0.0, result.bestValue(), 1e-9);
        assertEquals(3, result.iterations());
        assertIterableEquals(List.of(0, 1, 2, 3), result.snapshots().keySet());
        assertFalse(result.snapshots().get(0) == result.bestX());
        assertTrue(result.success());
    }
}
