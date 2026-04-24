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

    @Test
    void rfPenaltyOnlyCaseHasExpectedClosedFormValue() {
        var segments = List.of(new PulseSegment(List.of(
            new PulseStep(new double[]{OptimisationHardwareLimits.B1_MAX, 0.0, 0.0, 0.0}, 1.0)
        )));
        var template = SequenceTemplate.finiteTrain(List.of(new ControlSegmentSpec(1.0, 0, 1, 5)));
        var geometry = OptimisationTestSupport.singlePointGeometry(0.0, 0.0);
        var problem = new OptimisationProblem(
            geometry,
            template,
            new ObjectiveSpec(ObjectiveMode.FULL_TRAIN, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0)
        );

        var evaluation = engine.evaluate(problem, segments);

        // value = rfPower / rfPowerRef.
        //   rfPower    = B1_MAX² (only controls[0] is non-zero) · 1 step · dt=1.
        //   rfPowerRef = (# of RF envelope channels) · totalDt = 2 · 1 = 2
        //                because the geometry has two REAL sources (RF I, RF Q)
        //                feeding a Modulator.
        double expected = OptimisationHardwareLimits.B1_MAX * OptimisationHardwareLimits.B1_MAX / 2.0;
        assertEquals(expected, evaluation.value(), 1e-12);
        assertEquals(2, evaluation.signalTrace().points().size());
    }

    @Test
    void gradientIsFiniteForIdentityLikeProblem() {
        var segments = List.of(new PulseSegment(List.of(
            new PulseStep(new double[]{0.0, 0.0, 0.0, 0.0}, 0.0)
        )));
        var template = SequenceTemplate.finiteTrain(List.of(new ControlSegmentSpec(1.0, 1, 0, 5)));
        var geometry = OptimisationTestSupport.singlePointGeometry(1.0, 0.0);
        var problem = new OptimisationProblem(
            geometry,
            template,
            new ObjectiveSpec(ObjectiveMode.FULL_TRAIN, 0.0, 0.0, 0.1, 0.0, 0.0, 0.0, 0.0)
        );

        var evaluation = engine.evaluate(problem, segments);
        var gradient = engine.gradient(problem, segments);

        assertTrue(Double.isFinite(evaluation.value()));
        assertNotNull(evaluation.signalTrace());
        assertEquals(problem.sequenceTemplate().flattenedLength(), gradient.length);
        for (double value : gradient) assertTrue(Double.isFinite(value));
    }
}
