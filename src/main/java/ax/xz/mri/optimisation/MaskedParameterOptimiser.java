package ax.xz.mri.optimisation;

import ax.xz.mri.model.sequence.PulseSegment;

import java.util.LinkedHashMap;
import java.util.List;

/** Java optimiser backend using masked controls, continuation, and L-BFGS-B. */
public final class MaskedParameterOptimiser implements OptimiserBackend {
    private final LbfgsbSolver solver;

    public MaskedParameterOptimiser() {
        this(new LbfgsbSolver());
    }

    public MaskedParameterOptimiser(LbfgsbSolver solver) {
        this.solver = solver;
    }

    @Override
    public OptimisationResult optimise(OptimisationRequest request, ObjectiveEngine engine) {
        double[] initialFull = PulseParameterCodec.flatten(request.initialSegments());
        var mask = MaskedVector.of(request.freeMask());
        double[] lowerFree = mask.pick(request.lowerBounds());
        double[] upperFree = mask.pick(request.upperBounds());
        double[] initialFree = mask.pick(initialFull);

        var snapshots = new LinkedHashMap<Integer, List<PulseSegment>>();
        snapshots.put(0, request.parameterisation().expandSegments(request.initialSegments()));

        if (mask.size() == 0) {
            var expanded = request.parameterisation().expandSegments(request.initialSegments());
            return new OptimisationResult(
                request.initialSegments(),
                expanded,
                new SnapshotSeries(snapshots),
                engine.simulateSignal(request.problem(), request.initialSegments()),
                0,
                0,
                engine.evaluate(request.problem(), request.initialSegments()).value(),
                true,
                "No free variables"
            );
        }

        double[] currentFree = initialFree.clone();
        double[] bestFull = initialFull.clone();
        double bestValue = Double.POSITIVE_INFINITY;
        int totalIterations = 0;
        int[] evaluations = {0};
        boolean success = true;
        String message = "Completed continuation";

        for (var stage : request.continuationSchedule().stages()) {
            final int iterationBase = totalIterations;
            var stageProblem = request.problem().withObjectiveSpec(
                request.problem().objectiveSpec().withRfSmoothMultiplier(stage.rfSmoothMultiplier())
            );
            var stageResult = solver.solve(
                xFree -> {
                    double[] full = mask.merge(initialFull, xFree);
                    List<PulseSegment> segments = PulseParameterCodec.split(full, request.problem().sequenceTemplate());
                    double value = engine.evaluate(stageProblem, segments).value();
                    double[] gradientFull = engine.gradient(stageProblem, segments);
                    evaluations[0]++;
                    return new LbfgsbSolver.ValueGradient(value, mask.pick(gradientFull));
                },
                currentFree,
                lowerFree,
                upperFree,
                stage.iterations(),
                (iteration, x, value) -> {
                    if (request.stopRequested().get()) return true;
                    int globalIteration = iterationBase + iteration;
                    if (globalIteration % request.snapshotEvery() == 0) {
                        double[] full = mask.merge(initialFull, x);
                        snapshots.put(globalIteration, request.parameterisation().expandSegments(
                            PulseParameterCodec.split(full, request.problem().sequenceTemplate())
                        ));
                    }
                    return false;
                }
            );
            currentFree = stageResult.x().clone();
            totalIterations += stageResult.iterations();
            double[] full = mask.merge(initialFull, currentFree);
            List<PulseSegment> segments = PulseParameterCodec.split(full, request.problem().sequenceTemplate());
            double value = engine.evaluate(stageProblem, segments).value();
            if (value < bestValue) {
                bestValue = value;
                bestFull = full.clone();
            }
            success &= stageResult.success();
            message = stageResult.message();
            snapshots.put(totalIterations, request.parameterisation().expandSegments(segments));
            if (!stageResult.success()) break;
        }

        List<PulseSegment> bestSegments = PulseParameterCodec.split(bestFull, request.problem().sequenceTemplate());
        List<PulseSegment> expanded = request.parameterisation().expandSegments(bestSegments);
        return new OptimisationResult(
            bestSegments,
            expanded,
            new SnapshotSeries(snapshots),
            engine.simulateSignal(request.problem(), bestSegments),
            totalIterations,
            evaluations[0],
            bestValue,
            success,
            message
        );
    }
}
