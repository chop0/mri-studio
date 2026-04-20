package ax.xz.mri.optimisation;

import ax.xz.mri.model.sequence.PulseSegment;
import ax.xz.mri.model.sequence.PulseStep;
import ax.xz.mri.model.simulation.SignalTrace;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaskedParameterOptimiserTest {
    @Test
    void optimisesOnlyFreeControlsAndRecordsSnapshots() {
        var template = SequenceTemplate.finiteTrain(List.of(new ControlSegmentSpec(1e-6, 2, 0, 5)));
        var initialSegments = List.of(new PulseSegment(List.of(
            new PulseStep(new double[]{0.0, 0.0, 0.0, 0.0}, 0.0),
            new PulseStep(new double[]{0.0, 0.0, 0.0, 0.0}, 0.0)
        )));
        double[] target = new double[]{0.25, 0.0, 0.0, 0.0, 0.0, -0.4, 0.0, 0.0, 0.0, 0.0};
        boolean[] freeMask = new boolean[]{true, false, false, false, false, true, false, false, false, false};
        double[] lower = new double[]{-1.0, -1.0, -1.0, -1.0, 0.0, -1.0, -1.0, -1.0, -1.0, 0.0};
        double[] upper = new double[]{1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0};
        var request = new OptimisationRequest(
            "Seed",
            "Out",
            OptimisationTestSupport.simpleProblem(template, OptimisationTestSupport.simpleObjective(ObjectiveMode.FULL_TRAIN)),
            initialSegments,
            new IdentityControlParameterisation(template),
            freeMask,
            lower,
            upper,
            new ContinuationSchedule(List.of(new ContinuationStage(20, 1.0))),
            1,
            new AtomicBoolean(false)
        );
        var engine = new QuadraticObjectiveEngine(target);

        var result = new MaskedParameterOptimiser().optimise(request, engine);
        double[] optimised = PulseParameterCodec.flatten(result.optimisedSegments());

        assertEquals(target[0], optimised[0], 1e-4);
        assertEquals(target[5], optimised[5], 1e-4);
        assertEquals(0.0, optimised[1], 1e-12);
        assertEquals(0.0, optimised[4], 1e-12);
        assertTrue(result.snapshots().snapshots().containsKey(0));
        assertTrue(result.snapshots().snapshots().containsKey(1));
        assertTrue(result.success());
    }

    private static final class QuadraticObjectiveEngine implements ObjectiveEngine {
        private final double[] target;

        private QuadraticObjectiveEngine(double[] target) {
            this.target = target.clone();
        }

        @Override
        public ObjectiveEvaluation evaluate(OptimisationProblem problem, List<PulseSegment> segments) {
            double[] values = PulseParameterCodec.flatten(segments);
            double value = 0.0;
            for (int index = 0; index < values.length; index++) {
                double delta = values[index] - target[index];
                value += delta * delta;
            }
            return new ObjectiveEvaluation(value, simulateSignal(problem, segments));
        }

        @Override
        public double[] gradient(OptimisationProblem problem, List<PulseSegment> segments) {
            double[] values = PulseParameterCodec.flatten(segments);
            double[] gradient = new double[values.length];
            for (int index = 0; index < values.length; index++) {
                gradient[index] = 2.0 * (values[index] - target[index]);
            }
            return gradient;
        }

        @Override
        public SignalTrace simulateSignal(OptimisationProblem problem, List<PulseSegment> segments) {
            return new SignalTrace(List.of(new SignalTrace.Point(0.0, 0.0)));
        }
    }
}
