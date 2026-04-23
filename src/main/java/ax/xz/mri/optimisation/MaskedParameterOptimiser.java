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
        boolean[] freeMask = request.freeMask().clone();
        int[] freeIndices = freeIndices(freeMask);
        double[] lowerFree = pick(request.lowerBounds(), freeIndices);
        double[] upperFree = pick(request.upperBounds(), freeIndices);
        double[] initialFree = pick(initialFull, freeIndices);

        var snapshots = new LinkedHashMap<Integer, List<PulseSegment>>();
        snapshots.put(0, request.parameterisation().expandSegments(request.initialSegments()));

        if (freeIndices.length == 0) {
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
                    double[] full = merge(initialFull, xFree, freeIndices);
                    List<PulseSegment> segments = PulseParameterCodec.split(full, request.problem().sequenceTemplate());
                    double value = engine.evaluate(stageProblem, segments).value();
                    double[] gradientFull = engine.gradient(stageProblem, segments);
                    evaluations[0]++;
                    return new LbfgsbSolver.ValueGradient(value, pick(gradientFull, freeIndices));
                },
                currentFree,
                lowerFree,
                upperFree,
                stage.iterations(),
                (iteration, x, value) -> {
                    if (request.stopRequested().get()) return true;
                    int globalIteration = iterationBase + iteration;
                    if (globalIteration % request.snapshotEvery() == 0) {
                        double[] full = merge(initialFull, x, freeIndices);
                        snapshots.put(globalIteration, request.parameterisation().expandSegments(
                            PulseParameterCodec.split(full, request.problem().sequenceTemplate())
                        ));
                    }
                    return false;
                }
            );
            currentFree = stageResult.x().clone();
            totalIterations += stageResult.iterations();
            double[] full = merge(initialFull, currentFree, freeIndices);
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

    private static int[] freeIndices(boolean[] freeMask) {
        int count = 0;
        for (boolean value : freeMask) if (value) count++;
        int[] indices = new int[count];
        int offset = 0;
        for (int index = 0; index < freeMask.length; index++) {
            if (freeMask[index]) indices[offset++] = index;
        }
        return indices;
    }

    private static double[] pick(double[] values, int[] indices) {
        double[] out = new double[indices.length];
        for (int i = 0; i < indices.length; i++) {
            out[i] = values[indices[i]];
        }
        return out;
    }

    private static double[] merge(double[] frozenFull, double[] freeValues, int[] freeIndices) {
        double[] merged = frozenFull.clone();
        for (int i = 0; i < freeIndices.length; i++) {
            merged[freeIndices[i]] = freeValues[i];
        }
        return merged;
    }
}
