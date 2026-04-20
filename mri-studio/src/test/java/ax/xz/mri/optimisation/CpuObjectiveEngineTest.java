package ax.xz.mri.optimisation;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpuObjectiveEngineTest {
    private final CpuObjectiveEngine engine = new CpuObjectiveEngine();
    private final ScenarioBuilder builder = new ScenarioBuilder();

    @Test
    void fullTrainEvaluationProducesFiniteValueSignalAndGradient() {
        var request = builder.buildRequest(
            OptimisationTestSupport.sampleDocument(),
            new ScenarioBuilder.BuildOptions(
                "Full GRAPE",
                "3",
                "Out",
                ObjectiveMode.FULL_TRAIN,
                0,
                FreeMaskMode.ALL,
                6,
                2,
                1,
                1
            )
        );

        var evaluation = engine.evaluate(request.problem(), request.initialSegments());
        var signal = engine.simulateSignal(request.problem(), request.initialSegments());
        var gradient = engine.gradient(request.problem(), request.initialSegments());
        int totalSteps = request.initialSegments().stream().mapToInt(segment -> segment.steps().size()).sum();

        assertTrue(Double.isFinite(evaluation.value()));
        assertNotNull(evaluation.signalTrace());
        assertEquals(totalSteps + 1, evaluation.signalTrace().points().size());
        assertEquals(evaluation.signalTrace().points(), signal.points());
        assertEquals(request.problem().sequenceTemplate().flattenedLength(), gradient.length);
        for (double value : gradient) assertTrue(Double.isFinite(value));
    }

    @Test
    void periodicCycleEvaluationUsesOnlyCycleStepsForSignalTrace() {
        var request = builder.buildRequest(
            OptimisationTestSupport.sampleDocument(),
            new ScenarioBuilder.BuildOptions(
                "Full GRAPE",
                "0",
                "Out",
                ObjectiveMode.PERIODIC_CYCLE,
                1,
                FreeMaskMode.ALL,
                6,
                2,
                1,
                1
            )
        );

        var evaluation = engine.evaluate(request.problem(), request.initialSegments());

        assertTrue(Double.isFinite(evaluation.value()));
        assertNotNull(evaluation.signalTrace());
        assertEquals(request.initialSegments().get(1).steps().size() + 1, evaluation.signalTrace().points().size());
    }

    @Test
    void rfPenaltyOnlyCaseHasExpectedClosedFormValue() {
        var segments = List.of(new PulseSegment(List.of(
            new PulseStep(new double[]{OptimisationHardwareLimits.B1_MAX, 0.0, 0.0, 0.0}, 1.0)
        )));
        var template = SequenceTemplate.finiteTrain(List.of(new ControlSegmentSpec(1.0, 0, 1, 5)));
        var geometry = OptimisationTestSupport.singlePointGeometry(0.0, 0.0, 0.0);
        var problem = new OptimisationProblem(
            geometry,
            template,
            new ObjectiveSpec(ObjectiveMode.FULL_TRAIN, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0)
        );

        var evaluation = engine.evaluate(problem, segments);

        // RF penalty normalisation: value = rfPower / rfPowerRef.
        // rfPower = (I² + Q²) · dt = B1_MAX² · 1.0. rfPowerRef = 1 per QUADRATURE pair · rfTimeRef = 1.0.
        double expected = OptimisationHardwareLimits.B1_MAX * OptimisationHardwareLimits.B1_MAX;
        assertEquals(expected, evaluation.value(), 1e-12);
        assertEquals(2, evaluation.signalTrace().points().size());
    }
}
